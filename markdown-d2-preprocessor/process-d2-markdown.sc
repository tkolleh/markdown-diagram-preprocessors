#!/bin/sh
# This wrapper finds scala-cli in common locations and executes the script
# It handles the "No such file or directory" error in GUI apps like Marked 2
#
# Add common install locations to PATH
export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"

exec scala-cli --power shebang "$0" "$@"
!#
//> using scala "2.13.18"
//> using jvm "corretto:21"
//> using platform "jvm"
//> using dep "com.lihaoyi::mainargs:0.7.6"
//> using dep "com.lihaoyi::os-lib:0.11.3"
//> using dep "com.lihaoyi::ujson:4.0.2"

import mainargs.{arg, ParserForClass, Flag}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Using
import scala.util.Try

case class Config(
  @arg(short = 'i', doc = "Input markdown file path (default: stdin)")
  inputFile: Option[String] = None,
  @arg(short = 'o', doc = "Output file path (default: stdout)")
  outputFile: Option[String] = None,
  @arg(short = 'v', doc = "Verbose mode")
  verbose: Flag = Flag()
)

case class D2CodeBlock(
  content: String,
  lineNumber: Int,
  absoluteOffset: Int,
  matchStart: Int,
  matchEnd: Int
)

def findD2Blocks(filePath: String, verbose: Boolean = false): List[D2CodeBlock] = {
  // Use ripgrep with JSON output to find d2 code blocks using structured parsing
  // This avoids regex string manipulation and provides precise location information
  val cmd = Seq(
    "rg",
    "--multiline",
    "--json",
    """```d2\n((?s).*?)\n```""",
    filePath
  )
  
  if (verbose) {
    System.err.println(s"Running: ${cmd.mkString(" ")}")
  }
  
  val output = new StringBuilder()
  
  // Run ripgrep and capture output
  val exitCode = Try {
    import sys.process._
    val result = cmd.!!
    output.append(result)
    0
  }.recover {
    case _: RuntimeException =>
      // Exit code 1 means no matches, which is fine
      1
  }.getOrElse(-1)
  
  if (exitCode < 0 || (exitCode != 0 && exitCode != 1)) {
    if (verbose) {
      System.err.println(s"ripgrep error (exit $exitCode)")
    }
    return List.empty
  }
  
  // Parse JSON output from ripgrep
  val blocks = output.toString.split("\n")
    .filter(_.trim.nonEmpty)
    .flatMap { line =>
      Try {
        val json = ujson.read(line)
        val msgType = json("type").str
        
        if (msgType == "match") {
          val data = json("data")
          val matchText = data("lines")("text").str
          val lineNum = data("line_number").num.toInt
          val absOffset = data("absolute_offset").num.toInt
          val submatches = data("submatches").arr
          
          if (submatches.nonEmpty) {
            val submatch = submatches.head
            val fullMatch = submatch("match")("text").str
            val start = submatch("start").num.toInt
            val end = submatch("end").num.toInt
            
            // Extract just the d2 code content (without the backticks)
            val codePattern = """```d2\n((?s).*?)\n```""".r
            val codeContent = codePattern.findFirstMatchIn(fullMatch) match {
              case Some(m) => m.group(1)
              case None => fullMatch
            }
            
            Some(D2CodeBlock(codeContent, lineNum, absOffset, start, end))
          } else {
            None
          }
        } else {
          None
        }
      }.toOption
    }
    .flatten
    .toList
  
  if (verbose) {
    System.err.println(s"Found ${blocks.length} d2 code blocks")
  }
  
  blocks
}

def renderD2ToString(code: String, verbose: Boolean = false): String = {
  import sys.process._
  
  val stdoutBytes = new ByteArrayOutputStream()
  val stderrBytes = new ByteArrayOutputStream()
  
  // Using a Managed approach for the Input Stream
  val result = Using(new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8))) { is =>
    // 'd2 - -' reads from stdin, writes to stdout
    val cmd = Seq("d2", "-", "-")
    val io = new ProcessIO(
      in => { 
        val buffer = new Array[Byte](4096)
        var bytesRead = 0
        while ({bytesRead = is.read(buffer); bytesRead != -1}) {
          in.write(buffer, 0, bytesRead)
        }
        in.close()
      },
      out => {
        val buffer = new Array[Byte](4096)
        var bytesRead = 0
        while ({bytesRead = out.read(buffer); bytesRead != -1}) {
          stdoutBytes.write(buffer, 0, bytesRead)
        }
        out.close()
      },
      err => {
        val buffer = new Array[Byte](4096)
        var bytesRead = 0
        while ({bytesRead = err.read(buffer); bytesRead != -1}) {
          stderrBytes.write(buffer, 0, bytesRead)
        }
        err.close()
      }
    )
    
    val process = Process(cmd).run(io)
    val exitCode = process.exitValue()
    
    if (verbose) {
      System.err.println(s"D2 process exit code: $exitCode")
    }
    
    if (exitCode == 0) {
      new String(stdoutBytes.toByteArray(), StandardCharsets.UTF_8)
    } else {
      val errorMsg = new String(stderrBytes.toByteArray(), StandardCharsets.UTF_8)
      if (verbose) {
        System.err.println(s"D2 Error: $errorMsg")
      }
      s"<div style='border: 1px solid red; padding: 10px; color: #a00;'><strong>D2 Error:</strong><pre>$errorMsg</pre></div>"
    }
  }

  // Ensure we handle the Try result and return the string
  result.getOrElse("<p>Error: Failed to initialize D2 rendering process.</p>")
}

// Parse configuration using mainargs
val config = ParserForClass[Config].constructOrExit(args.toSeq)

// For structured parsing with ripgrep, we need a file path
// If reading from stdin, write to a temp file first
val inputPath = config.inputFile match {
  case Some(path) => 
    if (config.verbose.value) System.err.println(s"Reading from file: $path")
    path
  case None =>
    if (config.verbose.value) System.err.println("Reading from stdin, creating temp file")
    val tempFile = Files.createTempFile("d2-markdown-", ".md").toFile
    tempFile.deleteOnExit()
    Using(scala.io.Source.fromInputStream(System.in)) { source =>
      val content = source.mkString
      os.write.over(os.Path(tempFile.getAbsolutePath), content)
    }
    tempFile.getAbsolutePath
}

// Read the full input content
val input = os.read(os.Path(inputPath))

// Find all d2 code blocks using structured parsing (ripgrep with JSON)
val d2Blocks = findD2Blocks(inputPath, config.verbose.value)

// Process the input by replacing d2 blocks with rendered SVG
// We need to replace in reverse order to maintain correct positions
val processed = d2Blocks.sortBy(-_.absoluteOffset).foldLeft(input) { (text, block) =>
  if (config.verbose.value) {
    System.err.println(s"Processing D2 block at line ${block.lineNumber} (${block.content.length} chars)")
  }
  
  val svgContent = renderD2ToString(block.content, config.verbose.value)
  
  val replacement = s"""<div class="d2-diagram" style="margin: 2em 0; display: flex; justify-content: center;">
     |$svgContent
     |</div>""".stripMargin
  
  // Find the full code block in the text (including backticks)
  val blockPattern = s"""```d2\n${java.util.regex.Pattern.quote(block.content)}\n```"""
  text.replaceFirst(blockPattern, java.util.regex.Matcher.quoteReplacement(replacement))
}

// Write output to file or stdout
config.outputFile match {
  case Some(path) => 
    if (config.verbose.value) System.err.println(s"Writing to file: $path")
    os.write.over(os.Path(path, os.pwd), processed)
  case None => 
    print(processed)
}
