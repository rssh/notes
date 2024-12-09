#!/usr/bin/env -S scala-cli -S 3

//> using scala "3.3.3"
//> using dep "com.mchange::audiofluidity-rss:0.0.6"
//> using dep "com.lihaoyi::os-lib::0.11.3"
//

val blogTitle = "Random notes about programming"
val author = "Ruslan Shevchenko <ruslan@shevchenko.kiev.ua>"
val baseUrl = "https://github.com/rssh/notes/blob/master"
val feedUrl = "https://rssh.github.io/notes/feed.xml"
val wd = os.pwd
val path = if wd.toString.endsWith("scripts") && os.exists(wd / "generate-feed.sc") then
              os.Path(wd.wrapped.getParent)
           else if os.exists(wd/"scripts"/"generate-feed.sc") then
              wd
           else   
              println(s"Can't determinate directory: should be scripts or current dirrectory, not in ${wd}, exiting")
              System.exit(1)
              ???

import audiofluidity.rss.*
import java.time.*

def extractTitle(lines:IndexedSeq[String]): Option[String] = {
  val titleLine = "^title: (.*)$".r
  val head1Line = """^\# (.*)$""".r
  val head2Line = """^\#\# (.*)$""".r
  lines.collectFirst{
    case titleLine(title) => title
    case head1Line(title) => title
    case head2Line(title) => title
  }
   
}
              
println(s"path=$path")
val items = os.list(path).filter(file => 
        os.isFile(file) && file.ext == "md" && 
        file.baseName != "README" 
    ).flatMap{ file =>
      val dateRegExpr = """([0-9]+)_([0-9]+)_([0-9]+)_(.*)$""".r
      file.baseName.toString match
        case dateRegExpr(sYear,sMonth,sDay, rest) =>
          val (month, day) = if (sMonth.toInt > 12) {
            (sDay.toInt, sMonth.toInt)
          } else (sMonth.toInt, sDay.toInt)
          val year = sYear.toInt
          val date = LocalDate.of(year,month,day)
          println(s"file=$file, date=$date")
          Some((file, date))
        case _ =>  
          println(s"file $file without date prefix, skipping")
          None
    }.sortBy(_._2).reverse.map{ (file, ctime) =>
      val mdContent = os.read.lines(file)
      val title = extractTitle(mdContent).getOrElse(file.toString)
      val pubDate = ZonedDateTime.of(ctime, LocalTime.MIN, ZoneId.systemDefault)
      Element.Item.create(title, s"${baseUrl}/${file.baseName}.${file.ext}", "at {}", author, pubDate=Some(pubDate))
    }

val channel = Element.Channel.create(blogTitle,feedUrl,"random unsorted notes",items) 
println(Element.Rss(channel).asXmlText)
val rss = Element.Rss(channel).asXmlText
os.write.over(path/"feed.xml",rss)

