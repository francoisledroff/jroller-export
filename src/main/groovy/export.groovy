import groovy.text.SimpleTemplateEngine
import org.htmlcleaner.*

import java.text.SimpleDateFormat
import java.util.regex.Pattern

def reformatDate(String date) {
    //lun. 31 janv. 2005
    def sdf = new SimpleDateFormat("EEE. dd MMM. yyyy", Locale.FRENCH)
    sdf.lenient = true
    def parsed = sdf.parse(date)
    sdf = new SimpleDateFormat('yyyy-MM-dd')
    sdf.format(parsed)
}

def processPage(String url) {
    def cleaner = new HtmlCleaner()
    def props = cleaner.properties
    props.translateSpecialEntities = true
    def serializer = new SimpleHtmlSerializer(props)
    def page = cleaner.clean(url.toURL())
    def metadata = [:].withDefault { '' }

    page.traverse { TagNode tagNode, htmlNode ->
        if (!metadata.contents && tagNode?.name=='div' && tagNode?.attributes?.class == 'post-content') {
            metadata.contents = tagNode
        }
        // tags
        // <p class="meta">Tags:  <a href="http://www.jroller.com/melix/tags/enseignement">enseignement</a> <a href="http://www.jroller.com/melix/tags/informatique">informatique</a></p>
        if (!metadata.tags && tagNode?.name=='div' && tagNode?.attributes?.class=='tags') {
            metadata.tags = tagNode.text
        }
        // next page
        if (!metadata.next && tagNode?.name=='div' && tagNode?.attributes?.class=='next-previous') {
            //def nexLink = tagNode.children[5].getAttributeByName("href")
                    //tagNode.childTagList.findAll { it.name == 'a' }
            def links = tagNode.childTagList.findAll { it.name == 'a' }
            metadata.next = links[-1].attributes.href
        }
        // date
        // <p class="meta">01:42PM Jul 26, 2007 in category <u>Java</u> by Cédric Champeau</p>
        // TODO <p class="post-date">lun. 31 janv. 2005</p>
        if (!metadata.publishedDate && tagNode?.name=='p' && tagNode?.attributes?.class=='post-date') {
            metadata.publishedDate = tagNode.text;
        }
        // title
        if (!metadata.title && tagNode?.name=='h2' && tagNode?.attributes?.class=='post-title') {
            def extractedTitle = tagNode.children[0].getAttributeByName("title").toString()
            // TODO regexp: Permanent Link: Java plus rapide, plus léger
            metadata.title = extractedTitle.substring(extractedTitle.indexOf(':')+1,extractedTitle.length())

            def extractedId = tagNode.children[0].getAttributeByName("href").toString()
            // TODO refexp: /page/francoisledroff/?anchor=java_plus_rapide_plus_léger
            metadata.id = extractedId.substring(extractedId.indexOf('=')+1,extractedId.length())

        }
        // remove prettyprint class
        if (tagNode?.attributes?.class=='prettyprint') {
            def children = tagNode.allChildren.collect {
                if (it instanceof TagNode && it.name=='br') {
                    new ContentNode('\n')
                } else if (it instanceof TagNode) {
                    // probably an error of markup
                    new ContentNode("&lt;$it.name&gt;")
                } else {
                    it
                }
            }
            tagNode.allChildren.clear()
            tagNode.allChildren.addAll(children)

        }
        true
    }

    if (metadata.contents) {
        // convert node to HTML
        def tagNode = metadata.contents
        StringWriter wrt = new StringWriter(tagNode.text.size()*2)
        serializer.write(tagNode, wrt, 'utf-8')
        metadata.contents = wrt.toString()

        def tmpHtml = File.createTempFile('export_', '.html')
        def tmpAdoc = File.createTempFile('export_', '.adoc')
        tmpHtml.write(metadata.contents, 'utf-8')
        "pandoc -f html -t asciidoc --smart --no-wrap --normalize -s $tmpHtml -o ${tmpAdoc}".execute().waitFor()
        metadata.contents = postProcessAsciiDoc(tmpAdoc.getText('utf-8'))
        [tmpHtml,tmpAdoc]*.delete()
    } else {
        println "Unable to find blog post contents for $url"
    }

    metadata
}

/**
 * After conversion with 'pandoc', we still have problems with source code, which is not properly rendered.
 * This method will convert it to a format which is understood by Asciidoctor.
 */
String postProcessAsciiDoc(String markup) {
    def p = Pattern.compile(/(?:code,prettyprint(?:.+?)code,prettyprint)\n(.+?)(?:-{5,})/, Pattern.DOTALL)
    p.matcher(markup).replaceAll '''[source]
----
$1
----
'''
}

def config = new ConfigSlurper().parse(this.class.getResource('config.groovy'))
def outputDir = new File(config.jbake_output, 'content')
def engine = new SimpleTemplateEngine()
def next = config.origin
while (next) {
    def template = engine.createTemplate(this.class.getResource('post.gsp'))
    def md = processPage(next)
    if (md.contents) {
        def rendered = template.make([
                author: config.author,
                *: md
        ])
        //def (full, dayOfWeek, day, month, year) = (md.publishedDate =~ /([a-z]{1,4}). ([0-9]{1,2}) ([a-z]{1,4}). ([0-9]{1,4})/)[0]
        def (full,year,month,day) = (md.publishedDate =~ /([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})/)[0]
        def postDir = new File(new File(new File(outputDir, year), month), day)
        postDir.mkdirs()
        def doc = new File(postDir, "${md.id}.adoc")
        rendered.writeTo(doc.newWriter('utf-8'))
        println "Exported $next"
    }
    next = md.next
    // be gentle to JRoller!
    Thread.sleep 10000
}
