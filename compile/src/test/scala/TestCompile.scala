package xsbt

import java.io.File
import java.net.URLClassLoader
import xsbti.TestCallback
import FileUtilities.withTemporaryDirectory

object TestCompile
{
	// skip 2.7.3 and 2.7.4 for speed
	def allVersions = List("2.7.2", "2.7.5", "2.8.0-SNAPSHOT")//List("2.7.2", "2.7.3", "2.7.4", "2.7.5", "2.8.0-SNAPSHOT")
	/** Tests running the compiler interface with the analyzer plugin with a test callback.  The test callback saves all information
	* that the plugin sends it for post-compile analysis by the provided function.*/
	def apply[T](scalaVersion: String, sources: Set[File], outputDirectory: File, options: Seq[String], superclassNames: Seq[String])(f: (TestCallback, CompileLogger) => T): T =
	{
		val testCallback = new TestCallback(superclassNames.toArray)
		val result = WithCompiler(scalaVersion) { (compiler, log) =>
			compiler(sources, Set.empty, outputDirectory, options, testCallback, 5, log)
			f(testCallback, log)
		}
		result
	}
	/** Tests running the compiler interface with the analyzer plugin.  The provided function is given a ClassLoader that can
	* load the compiled classes..*/
	def apply[T](scalaVersion: String, sources: Seq[File])(f: ClassLoader => T): T =
		CallbackTest.apply(scalaVersion, sources, Nil){ case (callback, outputDir, log) => f(new URLClassLoader(Array(outputDir.toURI.toURL))) }
}
object CallbackTest
{
	def apply[T](scalaVersion: String, sources: Iterable[File])(f: TestCallback => T): T =
		apply(scalaVersion, sources.toSeq, Nil){ case (callback, outputDir, log) => f(callback) }
	def apply[T](scalaVersion: String, sources: Seq[File], superclassNames: Seq[String])(f: (TestCallback, File, CompileLogger) => T): T =
		withTemporaryDirectory { outputDir =>
			TestCompile(scalaVersion, Set() ++ sources, outputDir, Nil, superclassNames) { case (callback, log) => f(callback, outputDir, log) }
		}
}