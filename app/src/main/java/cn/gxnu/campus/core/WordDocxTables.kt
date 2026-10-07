package cn.gxnu.campus.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads an OOXML .docx package into a [WordDocument].
 *
 * FROZEN SIGNATURE — replaced in place by the package-format implementation. Callers only ever use
 * [WordDocuments.read], so nothing else needs to know how the text is recovered.
 *
 * A .docx is a ZIP package whose body sits in `word/document.xml`, as a WordprocessingML tree: the
 * printed 课表 is one `w:tbl`, its `w:tr` rows and `w:tc` cells, and each cell's `w:p` paragraphs.
 * Only the XML is read — styles, numbering and the rest of the package say nothing about the grid.
 */
internal object WordDocxTables {

    /**
     * A body past this is refused rather than inflated into memory; a printed timetable is tens of
     * kilobytes. The cap is on the decompressed entry, so a small archive cannot expand without end.
     */
    private const val MAX_BODY_BYTES = 32 * 1024 * 1024

    private const val BODY = "body"
    private const val TABLE = "tbl"
    private const val ROW = "tr"
    private const val CELL = "tc"
    private const val CELL_PROPERTIES = "tcPr"
    private const val VERTICAL_MERGE = "vMerge"
    private const val PARAGRAPH = "p"
    private const val TEXT = "t"

    fun read(bytes: ByteArray): WordDocument? {
        val body = bodyOf(documentEntry(bytes) ?: return null) ?: return null
        return WordDocument(childrenNamed(body, TABLE).map(::tableOf))
    }

    /** The package's `word/document.xml`, or null when the bytes hold no readable such entry. */
    private fun documentEntry(bytes: ByteArray): ByteArray? = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name.trimStart('/') != WordDocuments.DOCX_DOCUMENT_ENTRY) {
                entry = zip.nextEntry
            }
            if (entry == null) null else zip.readEntry()
        }
    } catch (unreadable: IOException) {
        null
    }

    /** The current entry's bytes, or null when it inflates past [MAX_BODY_BYTES]. */
    private fun ZipInputStream.readEntry(): ByteArray? {
        val body = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read < 0) return body.toByteArray()
            if (body.size() + read > MAX_BODY_BYTES) return null
            body.write(buffer, 0, read)
        }
    }

    /** The parsed `w:body` of a body part, or null when the bytes are not one. */
    private fun bodyOf(xml: ByteArray): Element? {
        // The package comes from a file the user picked: no DTD and no external entity is resolved,
        // so a document that declares one is refused rather than expanded.
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (broken: Exception) {
            // Whatever the reason — truncated XML, no such feature, no body at all — the answer is
            // that this file is not a document this reader understands.
            return null
        }
        return childrenNamed(document.documentElement, BODY).firstOrNull()
    }

    /** One `w:tbl` as a [WordTable]. A table nested inside a cell is left where it is. */
    private fun tableOf(table: Element): WordTable =
        WordTable(childrenNamed(table, ROW).map(::rowOf))

    private fun rowOf(row: Element): List<WordCell> = childrenNamed(row, CELL).map(::cellOf)

    /**
     * A cell as the contract's two shapes. The vertical merge keeps a course running through the rows
     * it spans: the first cell of the block is written `<w:vMerge w:val="restart"/>` and holds the
     * text, and every row below it repeats the cell with a bare `<w:vMerge/>` — or no `val` at all —
     * which is the continuation this reader reports as [WordCell.continues] with nothing of its own.
     */
    private fun cellOf(cell: Element): WordCell {
        val merge = childrenNamed(cell, CELL_PROPERTIES)
            .firstOrNull()
            ?.let { childrenNamed(it, VERTICAL_MERGE).firstOrNull() }
        if (merge != null && attributeOf(merge, "val") != "restart") {
            return WordCell(lines = emptyList(), continues = true)
        }
        return WordCell(lines = childrenNamed(cell, PARAGRAPH).flatMap(::linesOf))
    }

    /**
     * One paragraph as its lines. A `<w:br/>` prints a line break inside a paragraph, so it starts a
     * line exactly as the paragraph ending does, and the cell's text is taken verbatim — neither run
     * nor paragraph is trimmed, which is what keeps the spaces `xml:space="preserve"` asks for (the
     * 节次 gutter prints `上` over `   午`, and a course cell a blank paragraph above the course).
     */
    private fun linesOf(paragraph: Element): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        forEachDescendant(paragraph) { element ->
            when (nameOf(element)) {
                TEXT -> line.append(element.textContent)
                "br", "cr" -> {
                    lines += line.toString()
                    line.setLength(0)
                }
            }
        }
        lines += line.toString()
        return lines
    }

    /** The element's name without any namespace prefix, so `w:tbl` and `tbl` read alike. */
    private fun nameOf(element: Element): String = element.localName ?: element.tagName

    /** [parent]'s direct element children called [name], in document order. */
    private fun childrenNamed(parent: Element, name: String): List<Element> {
        val children = mutableListOf<Element>()
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (nameOf(element) == name) children += element
            }
            child = child.nextSibling
        }
        return children
    }

    /** Every element below [element], in document order, so a paragraph's runs read as they print. */
    private fun forEachDescendant(element: Element, action: (Element) -> Unit) {
        var child = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val childElement = child as Element
                action(childElement)
                forEachDescendant(childElement, action)
            }
            child = child.nextSibling
        }
    }

    /** The attribute's value, plainly written or prefixed; null when the element does not carry it. */
    private fun attributeOf(element: Element, name: String): String? {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.nodeName == name || attribute.localName == name) return attribute.nodeValue
        }
        return null
    }
}
