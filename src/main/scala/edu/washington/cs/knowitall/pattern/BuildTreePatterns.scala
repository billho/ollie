package edu.washington.cs.knowitall
package pattern

import java.io.File
import java.io.PrintWriter
import scala.io.Source
import scala.collection
import common.Timing._
import TreePatternLearner.findPattern
import tool.parse._
import tool.stem._
import tool.parse.pattern._
import tool.parse.graph._
import util.DefaultObjects
import org.slf4j.LoggerFactory

import scopt.OptionParser

object BuildTreePatterns {
  import TreePatternLearner._

  val logger = LoggerFactory.getLogger(this.getClass)

  val CHUNK_SIZE = 100000

  class Settings {
    var sourcePath: String = _
    var destPath: Option[String] = None
    var length = Option.empty[Int]
    var parallel = false
  }

  def main(args: Array[String]) {
    val settings = new Settings

    val parser = new OptionParser("buildpats") {
      arg("source", "source", { v: String => settings.sourcePath = v })
      argOpt("dest", "dest", { v: String => settings.destPath = Some(v) })
      opt("p", "parallel", "run multithreaded", { settings.parallel = true })
      intOpt("l", "length", "<length>", "maximum number of edges in the patterns", { l: Int => settings.length = Some(l) })
    }
    if (parser.parse(args)) {
      logger.debug("info: " + args.mkString(" "))
      main(settings)
    }
  }
 
 def main(settings: Settings) {
   def validGraph(graph: DependencyGraph) = {
     // make sure there is a verb
     graph.nodes.exists(node => "(?i)^VB".r.findFirstIn(node.postag).isDefined)
   }
   
    // file with dependencies
    val source = Source.fromFile(settings.sourcePath, "UTF-8")
    val writer = settings.destPath.map(dest => new PrintWriter(new File(dest), "UTF8")).getOrElse(new PrintWriter(System.out))
    
    logger.info("chunk size: " + CHUNK_SIZE)
    logger.info("pattern length: " + settings.length)

    var index = 0
    for (lines <- source.getLines.grouped(CHUNK_SIZE)) {
      @volatile var count = 0

      val group = if (settings.parallel) lines.par else lines

      val ms = time(group.foreach { line =>
        val Array(rel, arg1, arg2, lemmaString, text, _/*lemmas*/, _/*postags*/, _/*chunks*/, deps) = line.split("\t")
        val lemmas = lemmaString.split("\\s+").toSet

        // todo: push stemming forward in the process
        try {
          val graph = DependencyGraph.deserialize(deps).map { node =>
            node.lemmatize(MorphaStemmer.instance)
          }.collapseNounGroups().collapseNNPOf.simplifyPostags

          if (!validGraph(graph)) {
            logger.warn("Invalid graph (no verb?): " + graph.text + "\t" + graph.serialize)
          }
          else {
            val patterns = findPatternsForLDA(graph, lemmas, Map(arg1 -> "arg1", arg2 -> "arg2"), rel, settings.length)
            for ((pattern, slots) <- patterns; if pattern.valid) {
              if (!settings.length.isDefined || pattern.nodeMatchers.length <= settings.length.get) {
                writer.println((List(rel, arg1, arg2, lemmas.mkString(" "), pattern, text, deps) ::: slots).mkString("\t"))
                count += 1
              }
            }
          }
        }
        catch {
          case e: NoRelationNodeException => logger.warn(e.toString)
          case e: DependencyGraph.SerializationException => 
            logger.error("could not deserialize graph: " + deps, e)
        }
      })

      logger.info("chunk " + index + ": " + count + " items in " + Seconds.format(ms))
      writer.flush()

      index += 1
    }

    logger.info("done.")

    source.close
    writer.close
  }
}

object KeepCommonPatterns {
  def main(args: Array[String]) {
    val min = args(1).toInt
    System.err.println("minimum pattern ocurrence: "+min)

    var rows = 0
    var keepers = 0

    var patterns = collection.immutable.Map[String, Int]().withDefaultValue(0)
    val firstSource = Source.fromFile(args(0), "UTF8")
    for (line <- firstSource.getLines) {
      val Array(rel, arg1, arg2, lemmas, pattern, text, deps, _*) = line.split("\t")
      rows += 1
      patterns += pattern -> (patterns(pattern) + 1)
    }
    firstSource.close()

    System.err.println(rows+" rows")
    System.err.println(patterns.size+" unique patterns")

    val secondSource = Source.fromFile(args(0), "UTF8")
    for (line <- secondSource.getLines) {
      val Array(rel, arg1, arg2, lemmas, pattern, text, deps, _*) = line.split("\t")
      if (patterns(pattern) >= min) {
        keepers += 1
        println(line)
      }
    }
    secondSource.close()

    System.err.println(keepers+" patterns that occur more than "+min+"times") 
  }
}

object KeepDiversePatterns {
  abstract class Settings {
    def inputFile: File
    def min: Int
    def outputFile: Option[File]
    def debugFile: Option[File]
  }
  
  def main(args: Array[String]) {
    val settings = new Settings {
      var inputFile: File = _
      var min: Int = 5
      var outputFile: Option[File] = None
      var debugFile: Option[File] = None
    }

    val parser = new OptionParser("buildpats") {
      arg("input", "input file", { path: String => settings.inputFile = new File(path) })
      intOpt("min", "minimum number of relations per pattern", { string: Int => settings.min })
      opt("debug", "debug output file", { path: String => settings.debugFile = Some(new File(path)) })
      opt("output", "output file", { path: String => settings.outputFile = Some(new File(path)) })
    }
    if (parser.parse(args)) {
      run(settings)
    }
  }
    
  def run(settings: Settings) {
    val min = settings.min
    System.err.println("minimum relations per pattern: "+min)

    var rows = 0
    var keepers = 0

    var patterns = collection.immutable.Map[String, Set[Int]]().withDefaultValue(Set())
    val firstSource = Source.fromFile(settings.inputFile, "UTF8")
    for (line <- firstSource.getLines) {
      val Array(rel, arg1, arg2, lemmas, pattern, text, deps, _*) = line.split("\t")
      rows += 1
      patterns += pattern -> (patterns(pattern) + rel.hashCode)
    }
    firstSource.close()

    System.err.println(rows+" rows")
    System.err.println(patterns.size+" unique patterns")
    
    val secondSource = Source.fromFile(settings.inputFile, "UTF8")
    val outputWriter = settings.outputFile.map(new PrintWriter(_)).getOrElse(new PrintWriter(System.out))
    val debugWriter = settings.debugFile.map(new PrintWriter(_))
    for (line <- secondSource.getLines) {
      val Array(rel, arg1, arg2, lemmas, pattern, text, deps, _*) = line.split("\t")
      val size = patterns(pattern).size
      if (size >= min) {
        keepers += 1
        outputWriter.println(line)
      }
      else {
        debugWriter.map(_.println(size+"\t"+pattern))
      }
    }
    debugWriter.map(_.close())
    outputWriter.close()
    secondSource.close()

    System.err.println(keepers+" patterns that occur more than "+min+"times") 
  }
}
