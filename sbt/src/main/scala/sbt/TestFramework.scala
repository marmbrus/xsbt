/* sbt -- Simple Build Tool
 * Copyright 2008, 2009  Steven Blundy, Mark Harrah, Josh Cough
 */
package sbt

	import java.net.URLClassLoader
	import org.scalatools.testing.{AnnotatedFingerprint, Fingerprint, SubclassFingerprint, TestFingerprint}
	import org.scalatools.testing.{Event, EventHandler, Framework, Runner, Runner2, Logger=>TLogger}
	import xsbt.ScalaInstance

object Result extends Enumeration
{
	val Error, Passed, Failed = Value
}

object TestFrameworks
{
	val ScalaCheck = new TestFramework("org.scalacheck.ScalaCheckFramework")
	val ScalaTest = new TestFramework("org.scalatest.tools.ScalaTestFramework")
	val Specs = new TestFramework("org.specs.runner.SpecsFramework")
	val JUnit = new TestFramework("com.novocode.junit.JUnitFramework")
	// These are compatibility frameworks included in the 'test-compat' library
	val ScalaCheckCompat = new TestFramework("sbt.impl.ScalaCheckFramework")
	val ScalaTestCompat = new TestFramework("sbt.impl.ScalaTestFramework")
	val SpecsCompat = new TestFramework("sbt.impl.SpecsFramework")
}

class TestFramework(val implClassName: String) extends NotNull
{
	def create(loader: ClassLoader, log: Logger): Option[Framework] =
	{
		try { Some(Class.forName(implClassName, true, loader).newInstance.asInstanceOf[Framework]) }
		catch { case e: ClassNotFoundException => log.debug("Framework implementation '" + implClassName + "' not present."); None }
	}
}
final class TestDefinition(val name: String, val fingerprint: Fingerprint) extends NotNull
{
	override def toString = "Test " + name + " : " + fingerprint
	override def equals(t: Any) =
		t match
		{
			case r: TestDefinition => name == r.name && TestFramework.matches(fingerprint, r.fingerprint)
			case _ => false
		}
}

final class TestRunner(framework: Framework, loader: ClassLoader, listeners: Seq[TestReportListener], log: Logger) extends NotNull
{
	private[this] val delegate = framework.testRunner(loader, listeners.flatMap(_.contentLogger).toArray)
	private[this] def run(testDefinition: TestDefinition, handler: EventHandler, args: Array[String]): Unit =
		(testDefinition.fingerprint, delegate) match
		{
			case (simple: TestFingerprint, _) => delegate.run(testDefinition.name, simple, handler, args)
			case (basic, runner2: Runner2) => runner2.run(testDefinition.name, basic, handler, args)
			case _ => error("Framework '" + framework + "' does not support test '" + testDefinition + "'")
		}

	final def run(testDefinition: TestDefinition, args: Seq[String]): Result.Value =
	{
		log.debug("Running " + testDefinition + " with arguments " + args.mkString(", "))
		val name = testDefinition.name
		def runTest() =
		{
			// here we get the results! here is where we'd pass in the event listener
			val results = new scala.collection.mutable.ListBuffer[Event]
			val handler = new EventHandler { def handle(e:Event){ results += e } }
			run(testDefinition, handler, args.toArray)
			val event = TestEvent(results)
			safeListenersCall(_.testEvent( event ))
			event.result
		}

		safeListenersCall(_.startGroup(name))
		try
		{
			val result = runTest().getOrElse(Result.Passed)
			safeListenersCall(_.endGroup(name, result))
			result
		}
		catch
		{
			case e =>
				safeListenersCall(_.endGroup(name, e))
				Result.Error
		}
	}

	protected def safeListenersCall(call: (TestReportListener) => Unit): Unit =
		TestFramework.safeForeach(listeners, log)(call)
}

final class NamedTestTask(val name: String, action: => Option[String]) extends NotNull { def run() = action }

object TestFramework
{
	def getTests(framework: Framework): Seq[Fingerprint] =
		framework.getClass.getMethod("tests").invoke(framework) match
		{
			case newStyle: Array[Fingerprint] => newStyle.toList
			case oldStyle: Array[TestFingerprint] => oldStyle.toList
			case _ => error("Could not call 'tests' on framework " + framework)
		}

	private val ScalaCompilerJarPackages = "scala.tools." :: "jline." :: "ch.epfl.lamp." :: "org.apache.avro." :: Nil

	private val TestStartName = "test-start"
	private val TestFinishName = "test-finish"
	
	private[sbt] def safeForeach[T](it: Iterable[T], log: Logger)(f: T => Unit): Unit =
		it.foreach(i => Control.trapAndLog(log){ f(i) } )

	def matches(a: Fingerprint, b: Fingerprint) =
		(a, b) match
		{
			case (a: SubclassFingerprint, b: SubclassFingerprint) => a.isModule == b.isModule && a.superClassName == b.superClassName
			case (a: AnnotatedFingerprint, b: AnnotatedFingerprint) => a.isModule == b.isModule && a.annotationName == b.annotationName
			case _ => false
		}

		import scala.collection.{immutable, Map, Set}

	def testTasks(frameworks: Seq[TestFramework],
		classpath: Iterable[Path],
		scalaInstance: ScalaInstance,
		tests: Seq[TestDefinition],
		log: Logger,
		listeners: Seq[TestReportListener],
		endErrorsEnabled: Boolean,
		setup: Iterable[ClassLoader => Option[String]],
		cleanup: Iterable[ClassLoader => Option[String]],
		testArgsByFramework: Map[TestFramework, Seq[String]]):
			(Iterable[NamedTestTask], Iterable[NamedTestTask], Iterable[NamedTestTask]) =
	{
		val (loader, tempDir) = createTestLoader(classpath, scalaInstance)
		val arguments = immutable.Map() ++
			( for(framework <- frameworks; created <- framework.create(loader, log)) yield
				(created, testArgsByFramework.getOrElse(framework, Nil)) )
		val cleanTmp = (_: ClassLoader) => FileUtilities.clean(tempDir, log)

		val mappedTests = testMap(arguments.keys.toList, tests, arguments)
		if(mappedTests.isEmpty)
			(new NamedTestTask(TestStartName, None) :: Nil, Nil, new NamedTestTask(TestFinishName, { log.info("No tests to run."); cleanTmp(loader) }) :: Nil )
		else
			createTestTasks(loader, mappedTests, log, listeners, endErrorsEnabled, setup, Seq(cleanTmp) ++ cleanup)
	}

	private def testMap(frameworks: Seq[Framework], tests: Seq[TestDefinition], args: Map[Framework, Seq[String]]):
		immutable.Map[Framework, (Set[TestDefinition], Seq[String])] =
	{
		import scala.collection.mutable.{HashMap, HashSet, Set}
		val map = new HashMap[Framework, Set[TestDefinition]]
		def assignTests(): Unit =
		{
			for(test <- tests if !map.values.exists(_.contains(test)))
			{
				def isTestForFramework(framework: Framework) = getTests(framework).exists {t => matches(t, test.fingerprint) }
				for(framework <- frameworks.find(isTestForFramework))
					map.getOrElseUpdate(framework, new HashSet[TestDefinition]) += test
			}
		}
		if(!frameworks.isEmpty)
			assignTests()
		(immutable.Map() ++ map) transform { (framework, tests) => (tests, args(framework)) }
	}
	private def createTasks[T](work: Iterable[T => Option[String]], baseName: String, input: T) =
		work.toList.zipWithIndex.map{ case (work, index) => new NamedTestTask(baseName + " " + (index+1), work(input)) }
		
	private def createTestTasks(loader: ClassLoader, tests: Map[Framework, (Set[TestDefinition], Seq[String])], log: Logger,
		listeners: Seq[TestReportListener], endErrorsEnabled: Boolean, setup: Iterable[ClassLoader => Option[String]],
		cleanup: Iterable[ClassLoader => Option[String]]) =
	{
		val testsListeners = listeners.filter(_.isInstanceOf[TestsListener]).map(_.asInstanceOf[TestsListener])
		def foreachListenerSafe(f: TestsListener => Unit): Unit = safeForeach(testsListeners, log)(f)
		
			import Result.{Error,Passed,Failed}
		object result
		{
			private[this] var value: Result.Value = Passed
			def apply() = synchronized { value }
			def update(v: Result.Value): Unit = synchronized { if(value != Error) value = v }
		}
		val startTask = new NamedTestTask(TestStartName, {foreachListenerSafe(_.doInit); None}) :: createTasks(setup, "Test setup", loader)
		val testTasks =
			tests flatMap { case (framework, (testDefinitions, testArgs)) =>
			
					val runner = new TestRunner(framework, loader, listeners, log)
					for(testDefinition <- testDefinitions) yield
					{
						def runTest() =
						{
							val oldLoader = Thread.currentThread.getContextClassLoader
							Thread.currentThread.setContextClassLoader(loader)
							try {
								runner.run(testDefinition, testArgs) match
								{
									case Error => result() = Error; Some("ERROR occurred during testing.")
									case Failed => result() = Failed; Some("Test FAILED")
									case _ => None
								}
							}
							finally {
								Thread.currentThread.setContextClassLoader(oldLoader)
							}
						}
						new NamedTestTask(testDefinition.name, runTest())
					}
			}
		def end() =
		{
			foreachListenerSafe(_.doComplete(result()))
			result() match
			{
				case Error => if(endErrorsEnabled) Some("ERROR occurred during testing.") else None
				case Failed => if(endErrorsEnabled) Some("One or more tests FAILED.") else None
				case Passed =>
				{
					log.info(" ")
					log.info("All tests PASSED.")
					None
				}
			}
		}
		val endTask = new NamedTestTask(TestFinishName, end() ) :: createTasks(cleanup, "Test cleanup", loader)
		(startTask, testTasks, endTask)
	}
	def createTestLoader(classpath: Iterable[Path], scalaInstance: ScalaInstance): (ClassLoader, Path) =
	{
		val filterCompilerLoader = new FilteredLoader(scalaInstance.loader, ScalaCompilerJarPackages)
		val interfaceFilter = (name: String) => name.startsWith("org.scalatools.testing.")
		val notInterfaceFilter = (name: String) => !interfaceFilter(name)
		val dual = new xsbt.DualLoader(filterCompilerLoader, notInterfaceFilter, x => true, getClass.getClassLoader, interfaceFilter, x => false)
		ClasspathUtilities.makeLoader(classpath, dual, scalaInstance)
	}
}
