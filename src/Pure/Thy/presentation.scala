/*  Title:      Pure/Thy/presentation.scala
    Author:     Makarius

HTML presentation of PIDE document content.
*/

package isabelle


import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.mutable


object Presentation
{
  /** HTML documents **/

  /* HTML context */

  sealed case class HTML_Document(title: String, content: String)

  abstract class HTML_Context
  {
    /* directory structure */

    def root_dir: Path
    def theory_session(name: Document.Node.Name): Sessions.Info

    def session_dir(info: Sessions.Info): Path =
      root_dir + Path.explode(info.chapter_session)
    def theory_path(name: Document.Node.Name): Path =
      session_dir(theory_session(name)) + Path.explode(name.theory_base_name).html
    def files_path(name: Document.Node.Name, path: Path): Path =
      theory_path(name).dir + Path.explode("files") + path.squash.html


    /* cached theory exports */

    val cache: Term.Cache = Term.Cache.make()

    private val already_presented = Synchronized(Set.empty[String])
    def register_presented(nodes: List[Document.Node.Name]): List[Document.Node.Name] =
      already_presented.change_result(presented =>
        (nodes.filterNot(name => presented.contains(name.theory)),
          presented ++ nodes.iterator.map(_.theory)))

    private val theory_cache = Synchronized(Map.empty[String, Export_Theory.Theory])
    def cache_theory(thy_name: String, make_thy: => Export_Theory.Theory): Export_Theory.Theory =
    {
      theory_cache.change_result(thys =>
      {
        thys.get(thy_name) match {
          case Some(thy) => (thy, thys)
          case None =>
            val thy = make_thy
            (thy, thys + (thy_name -> thy))
        }
      })
    }


    /* HTML content */

    def head(title: String, rest: XML.Body = Nil): XML.Tree =
      HTML.div("head", HTML.chapter(title) :: rest)

    def source(body: XML.Body): XML.Tree = HTML.pre("source", body)

    def contents(heading: String, items: List[XML.Body], css_class: String = "contents")
      : List[XML.Elem] =
    {
      if (items.isEmpty) Nil
      else List(HTML.div(css_class, List(HTML.section(heading), HTML.itemize(items))))
    }

    val isabelle_css: String = File.read(HTML.isabelle_css)

    def html_document(title: String, body: XML.Body, fonts_css: String): HTML_Document =
    {
      val content =
        HTML.output_document(
          List(
            HTML.style(fonts_css + "\n\n" + isabelle_css),
            HTML.title(title)),
          List(HTML.source(body)), css = "", structural = false)
      HTML_Document(title, content)
    }
  }


  /* presentation elements */

  sealed case class Elements(
    html: Markup.Elements = Markup.Elements.empty,
    entity: Markup.Elements = Markup.Elements.empty,
    language: Markup.Elements = Markup.Elements.empty)

  val elements1: Elements =
    Elements(
      html = Rendering.foreground_elements ++ Rendering.text_color_elements +
        Markup.NUMERAL + Markup.COMMENT + Markup.ENTITY + Markup.LANGUAGE,
      entity = Markup.Elements(Markup.THEORY, Markup.TYPE_NAME, Markup.CONSTANT, Markup.FACT,
        Markup.CLASS, Markup.LOCALE, Markup.FREE))

  val elements2: Elements =
    Elements(
      html = elements1.html ++ Rendering.markdown_elements,
      language = Markup.Elements(Markup.Language.DOCUMENT))


  /* formal entities */

  type Entity = Export_Theory.Entity[Export_Theory.No_Content]

  object Entity_Context
  {
    object Theory_Ref
    {
      def unapply(props: Properties.T): Option[Document.Node.Name] =
        (props, props, props) match {
          case (Markup.Kind(Markup.THEORY), Markup.Name(theory), Position.Def_File(thy_file)) =>
            Some(Resources.file_node(Path.explode(thy_file), theory = theory))
          case _ => None
        }
    }

    object Entity_Ref
    {
      def unapply(props: Properties.T): Option[(Path, Option[String], String, String)] =
        (props, props, props, props) match {
          case (Markup.Ref(_), Position.Def_File(def_file), Markup.Kind(kind), Markup.Name(name)) =>
            val def_theory = Position.Def_Theory.unapply(props)
            Some((Path.explode(def_file), def_theory, kind, name))
          case _ => None
        }
    }

    val empty: Entity_Context = new Entity_Context

    def make(
        session: String,
        deps: Sessions.Deps,
        node: Document.Node.Name,
        theory_exports: Map[String, Export_Theory.Theory]): Entity_Context =
      new Entity_Context {
        private val seen_ranges: mutable.Set[Symbol.Range] = mutable.Set.empty

        override def make_def(range: Symbol.Range, body: XML.Body): Option[XML.Elem] =
        {
          body match {
            case List(XML.Elem(Markup("span", List("id" -> _)), _)) => None
            case _ =>
              Some {
                val entities =
                  theory_exports.get(node.theory).flatMap(_.entity_by_range.get(range))
                    .getOrElse(Nil)
                val body1 =
                  if (seen_ranges.contains(range)) {
                    HTML.entity_def(HTML.span(HTML.id(offset_id(range)), body))
                  }
                  else HTML.span(body)
                entities.map(_.kname).foldLeft(body1) {
                  case (elem, id) => HTML.entity_def(HTML.span(HTML.id(id), List(elem)))
                }
              }
          }
        }

        private def offset_id(range: Text.Range): String =
          "offset_" + range.start + ".." + range.stop

        private def physical_ref(thy_name: String, props: Properties.T): Option[String] =
        {
          for {
            range <- Position.Def_Range.unapply(props)
            if thy_name == node.theory
          } yield {
            seen_ranges += range
            offset_id(range)
          }
        }

        private def logical_ref(thy_name: String, kind: String, name: String): Option[String] =
          for {
            thy <- theory_exports.get(thy_name)
            entity <- thy.entity_by_kind_name.get((kind, name))
          } yield entity.kname

        override def make_ref(props: Properties.T, body: XML.Body): Option[XML.Elem] =
        {
          props match {
            case Theory_Ref(node_name) =>
              node_relative(deps, session, node_name).map(html_dir =>
                HTML.link(html_dir + html_name(node_name), body))
            case Entity_Ref(file_path, def_theory, kind, name) =>
              for {
                thy_name <-
                  def_theory orElse (if (File.eq(node.path, file_path)) Some(node.theory) else None)
                node_name = Resources.file_node(file_path, theory = thy_name)
                html_dir <- node_relative(deps, session, node_name)
                html_file = node_file(node_name)
                html_ref <-
                  logical_ref(thy_name, kind, name) orElse physical_ref(thy_name, props)
              } yield {
                HTML.entity_ref(HTML.link(html_dir + html_file + "#" + html_ref, body))
              }
            case _ => None
          }
        }
      }
  }

  class Entity_Context
  {
    def make_def(range: Symbol.Range, body: XML.Body): Option[XML.Elem] = None
    def make_ref(props: Properties.T, body: XML.Body): Option[XML.Elem] = None
  }


  /* HTML output */

  private val div_elements =
    Set(HTML.div.name, HTML.pre.name, HTML.par.name, HTML.list.name, HTML.enum.name,
      HTML.descr.name)

  def make_html(
    entity_context: Entity_Context,
    elements: Elements,
    xml: XML.Body): XML.Body =
  {
    def html_div(html: XML.Body): Boolean =
      html exists {
        case XML.Elem(markup, body) => div_elements.contains(markup.name) || html_div(body)
        case XML.Text(_) => false
      }

    def html_class(c: String, html: XML.Body): XML.Body =
      if (c == "") html
      else if (html_div(html)) List(HTML.div(c, html))
      else List(HTML.span(c, html))

    def html_body(xml_body: XML.Body, end_offset: Symbol.Offset): (XML.Body, Symbol.Offset) =
      xml_body.foldRight((List.empty[XML.Tree], end_offset)) { case (tree, (res, end_offset1)) =>
        val (res1, offset) = html_body_single(tree, end_offset1)
        (res1 ++ res, offset)
      }

    @tailrec
    def html_body_single(xml_tree: XML.Tree, end_offset: Symbol.Offset): (XML.Body, Symbol.Offset) =
      xml_tree match {
        case XML.Wrapped_Elem(markup, _, body) => html_body_single(XML.Elem(markup, body), end_offset)
        case XML.Elem(Markup(Markup.ENTITY, props @ Markup.Kind(kind)), body) =>
          val (body1, offset) = html_body(body, end_offset)
          if (elements.entity(kind)) {
            entity_context.make_ref(props, body1) match {
              case Some(link) => (List(link), offset)
              case None => (body1, offset)
            }
          }
          else (body1, offset)
        case XML.Elem(Markup(Markup.LANGUAGE, Markup.Name(name)), body) =>
          val (body1, offset) = html_body(body, end_offset)
          (html_class(if (elements.language(name)) name else "", body1), offset)
        case XML.Elem(Markup(Markup.MARKDOWN_PARAGRAPH, _), body) =>
          val (body1, offset) = html_body(body, end_offset)
          (List(HTML.par(body1)), offset)
        case XML.Elem(Markup(Markup.MARKDOWN_ITEM, _), body) =>
          val (body1, offset) = html_body(body, end_offset)
          (List(HTML.item(body1)), offset)
        case XML.Elem(Markup(Markup.Markdown_Bullet.name, _), text) =>
          (Nil, end_offset - XML.symbol_length(text))
        case XML.Elem(Markup.Markdown_List(kind), body) =>
          val (body1, offset) = html_body(body, end_offset)
          if (kind == Markup.ENUMERATE) (List(HTML.enum(body1)), offset)
          else (List(HTML.list(body1)), offset)
        case XML.Elem(markup, body) =>
          val name = markup.name
          val (body1, offset) = html_body(body, end_offset)
          val html =
            markup.properties match {
              case Markup.Kind(kind) if kind == Markup.COMMAND || kind == Markup.KEYWORD =>
                html_class(kind, body1)
              case _ =>
                body1
            }
          Rendering.foreground.get(name) orElse Rendering.text_color.get(name) match {
            case Some(c) => (html_class(c.toString, html), offset)
            case None => (html_class(name, html), offset)
          }
        case XML.Text(text) =>
          val offset = end_offset - Symbol.length(text)
          val body = HTML.text(Symbol.decode(text))
          entity_context.make_def(Text.Range(offset, end_offset), body) match {
            case Some(body1) => (List(body1), offset)
            case None => (body, offset)
          }
      }

    html_body(xml, XML.symbol_length(xml) + 1)._1
  }


  /* PIDE HTML document */

  def html_document(
    snapshot: Document.Snapshot,
    html_context: HTML_Context,
    elements: Elements,
    plain_text: Boolean = false,
    fonts_css: String = HTML.fonts_css()): HTML_Document =
  {
    require(!snapshot.is_outdated, "document snapshot outdated")

    val name = snapshot.node_name
    if (plain_text) {
      val title = "File " + Symbol.cartouche_decoded(name.path.file_name)
      val body = HTML.text(snapshot.node.source)
      html_context.html_document(title, body, fonts_css)
    }
    else {
      Resources.html_document(snapshot) getOrElse {
        val title =
          if (name.is_theory) "Theory " + quote(name.theory_base_name)
          else "File " + Symbol.cartouche_decoded(name.path.file_name)
        val xml = snapshot.xml_markup(elements = elements.html)
        val body = make_html(Entity_Context.empty, elements, xml)
        html_context.html_document(title, body, fonts_css)
      }
    }
  }



  /** HTML presentation **/

  /* presentation context */

  object Context
  {
    val none: Context = new Context { def enabled: Boolean = false }
    val standard: Context = new Context { def enabled: Boolean = true }

    def dir(path: Path): Context =
      new Context {
        def enabled: Boolean = true
        override def dir(store: Sessions.Store): Path = path
      }

    def make(s: String): Context =
      if (s == ":") standard else dir(Path.explode(s))
  }

  abstract class Context private
  {
    def enabled: Boolean
    def enabled(info: Sessions.Info): Boolean = enabled || info.browser_info
    def dir(store: Sessions.Store): Path = store.presentation_dir
    def dir(store: Sessions.Store, info: Sessions.Info): Path =
      dir(store) + Path.explode(info.chapter_session)
  }


  /* maintain chapter index */

  private val sessions_path = Path.basic(".sessions")

  private def read_sessions(dir: Path): List[(String, String)] =
  {
    val path = dir + sessions_path
    if (path.is_file) {
      import XML.Decode._
      list(pair(string, string))(Symbol.decode_yxml(File.read(path)))
    }
    else Nil
  }

  def update_chapter(
    presentation_dir: Path, chapter: String, new_sessions: List[(String, String)]): Unit =
  {
    val dir = Isabelle_System.make_directory(presentation_dir + Path.basic(chapter))

    val sessions0 =
      try { read_sessions(dir) }
      catch { case _: XML.Error => Nil }

    val sessions = (SortedMap.empty[String, String] ++ sessions0 ++ new_sessions).toList
    File.write(dir + sessions_path,
      {
        import XML.Encode._
        YXML.string_of_body(list(pair(string, string))(sessions))
      })

    val title = "Isabelle/" + chapter + " sessions"
    HTML.write_document(dir, "index.html",
      List(HTML.title(title + Isabelle_System.isabelle_heading())),
      HTML.chapter(title) ::
       (if (sessions.isEmpty) Nil
        else
          List(HTML.div("sessions",
            List(HTML.description(
              sessions.map({ case (name, description) =>
                val descr = Symbol.trim_blank_lines(description)
                (List(HTML.link(name + "/index.html", HTML.text(name))),
                  if (descr == "") Nil
                  else HTML.break ::: List(HTML.pre(HTML.text(descr)))) })))))),
      base = Some(presentation_dir))
  }

  def update_root(presentation_dir: Path): Unit =
  {
    Isabelle_System.make_directory(presentation_dir)
    HTML.init_fonts(presentation_dir)
    Isabelle_System.copy_file(Path.explode("~~/lib/logo/isabelle.gif"),
      presentation_dir + Path.explode("isabelle.gif"))
    val title = "The " + XML.text(Isabelle_System.isabelle_name()) + " Library"
    File.write(presentation_dir + Path.explode("index.html"),
      HTML.header +
"""
<head>
  """ + HTML.head_meta + """
  <title>""" + title + """</title>
</head>

<body text="#000000" bgcolor="#FFFFFF" link="#0000FF" vlink="#000099" alink="#404040">
  <center>
    <table width="100%" border="0" cellspacing="10" cellpadding="0">
      <tr>
        <td width="20%" valign="middle" align="center"><a href="https://isabelle.in.tum.de/"><img align="bottom" src="isabelle.gif" width="100" height="86" alt="[Isabelle]" border="0" /></a></td>

        <td width="80%" valign="middle" align="center">
          <table width="90%" border="0" cellspacing="0" cellpadding="20">
            <tr>
              <td valign="middle" align="center" bgcolor="#AACCCC"><font face="Helvetica,Arial" size="+2">""" + title + """</font></td>
            </tr>
          </table>
        </td>
      </tr>
    </table>
  </center>
  <hr />
""" + File.read(Path.explode("~~/lib/html/library_index_content.template")) +
"""
</body>
""" + HTML.footer)
  }


  /* present session */

  val session_graph_path = Path.explode("session_graph.pdf")
  val readme_path = Path.explode("README.html")

  def html_name(name: Document.Node.Name): String = Path.explode(name.theory_base_name).html.implode
  def files_path(src_path: Path): String = (Path.explode("files") + src_path.squash.html).implode

  private def node_file(name: Document.Node.Name): String =
    if (name.node.endsWith(".thy")) html_name(name) else files_path(name.path)

  private def session_relative(deps: Sessions.Deps, session0: String, session1: String): Option[String] =
  {
    for {
      info0 <- deps.sessions_structure.get(session0)
      info1 <- deps.sessions_structure.get(session1)
    } yield info0.relative_path(info1)
  }

  def node_relative(
    deps: Sessions.Deps,
    session0: String,
    node_name: Document.Node.Name): Option[String] =
  {
    val session1 = deps(session0).theory_qualifier(node_name)
    session_relative(deps, session0, session1)
  }

  def theory_link(deps: Sessions.Deps, session0: String,
    name: Document.Node.Name, body: XML.Body, anchor: Option[String] = None): Option[XML.Tree] =
  {
    val session1 = deps(session0).theory_qualifier(name)
    val info0 = deps.sessions_structure.get(session0)
    val info1 = deps.sessions_structure.get(session1)
    val fragment = if (anchor.isDefined) "#" + anchor.get else ""
    if (info0.isDefined && info1.isDefined) {
      Some(HTML.link(info0.get.relative_path(info1.get) + html_name(name) + fragment, body))
    }
    else None
  }

  def session_html(
    session: String,
    deps: Sessions.Deps,
    db_context: Sessions.Database_Context,
    progress: Progress = new Progress,
    verbose: Boolean = false,
    html_context: HTML_Context,
    session_elements: Elements): Unit =
  {
    val hierarchy = deps.sessions_structure.hierarchy(session)
    val info = deps.sessions_structure(session)
    val options = info.options
    val base = deps(session)

    val session_dir = Isabelle_System.make_directory(html_context.session_dir(info))

    Bytes.write(session_dir + session_graph_path,
      graphview.Graph_File.make_pdf(options, base.session_graph_display))

    val documents =
      for {
        doc <- info.document_variants
        document <- db_context.input_database(session)(Document_Build.read_document(_, _, doc.name))
      } yield {
        val doc_path = (session_dir + doc.path.pdf).expand
        if (verbose) progress.echo("Presenting document " + session + "/" + doc.name)
        if (options.bool("document_echo")) progress.echo("Document at " + doc_path)
        Bytes.write(doc_path, document.pdf)
        doc
      }

    val view_links =
    {
      val deps_link =
        HTML.link(session_graph_path, HTML.text("theory dependencies"))

      val readme_links =
        if ((info.dir + readme_path).is_file) {
          Isabelle_System.copy_file(info.dir + readme_path, session_dir + readme_path)
          List(HTML.link(readme_path, HTML.text("README")))
        }
        else Nil

      val document_links =
        documents.map(doc => HTML.link(doc.path.pdf, HTML.text(doc.name)))

      Library.separate(HTML.break ::: HTML.nl,
        (deps_link :: readme_links ::: document_links).
          map(link => HTML.text("View ") ::: List(link))).flatten
    }

    val all_used_theories = hierarchy.reverse.flatMap(a => deps(a).used_theories.map(_._1))
    val present_theories = html_context.register_presented(all_used_theories)

    val theory_exports: Map[String, Export_Theory.Theory] =
      (for (node <- all_used_theories.iterator) yield {
        val thy_name = node.theory
        val theory =
          if (thy_name == Thy_Header.PURE) Export_Theory.no_theory
          else {
            html_context.cache_theory(thy_name,
              {
                val provider = Export.Provider.database_context(db_context, hierarchy, thy_name)
                if (Export_Theory.read_theory_parents(provider, thy_name).isDefined) {
                  Export_Theory.read_theory(
                    provider, session, thy_name, cache = html_context.cache)
                }
                else Export_Theory.no_theory
              })
        }
        thy_name -> theory
      }).toMap

    def entity_context(name: Document.Node.Name): Entity_Context =
      Entity_Context.make(session, deps, name, theory_exports)

    val theories: List[XML.Body] =
    {
      sealed case class Seen_File(
        src_path: Path, thy_name: Document.Node.Name, thy_session: String)
      {
        val files_path: Path = html_context.files_path(thy_name, src_path)

        def check(src_path1: Path, thy_name1: Document.Node.Name, thy_session1: String): Boolean =
        {
          val files_path1 = html_context.files_path(thy_name1, src_path1)
          (src_path == src_path1 || files_path == files_path1) && thy_session == thy_session1
        }
      }
      var seen_files = List.empty[Seen_File]

      sealed case class Theory(
        name: Document.Node.Name,
        command: Command,
        files_html: List[(Path, XML.Tree)],
        html: XML.Tree)

      def read_theory(name: Document.Node.Name): Option[Theory] =
      {
        progress.expose_interrupt()

        for (command <- Build_Job.read_theory(db_context, hierarchy, name.theory))
        yield {
          if (verbose) progress.echo("Presenting theory " + name)
          val snapshot = Document.State.init.snippet(command)

          val thy_elements =
            session_elements.copy(entity =
              theory_exports(name.theory).others.keySet.foldLeft(session_elements.entity)(_ + _))

          val files_html =
            for {
              (src_path, xml) <- snapshot.xml_markup_blobs(elements = thy_elements.html)
              if xml.nonEmpty
            }
            yield {
              progress.expose_interrupt()
              if (verbose) progress.echo("Presenting file " + src_path)

              (src_path, html_context.source(
                make_html(entity_context(name), thy_elements, xml)))
            }

          val html =
            html_context.source(
              make_html(entity_context(name), thy_elements,
                snapshot.xml_markup(elements = thy_elements.html)))

          Theory(name, command, files_html, html)
        }
      }

      (for (thy <- Par_List.map(read_theory, present_theories).flatten) yield {
        val thy_session = html_context.theory_session(thy.name)
        val thy_dir = Isabelle_System.make_directory(html_context.session_dir(thy_session))
        val files =
          for { (src_path, file_html) <- thy.files_html }
          yield {
            seen_files.find(_.check(src_path, thy.name, thy_session.name)) match {
              case None => seen_files ::= Seen_File(src_path, thy.name, thy_session.name)
              case Some(seen_file) =>
                error("Incoherent use of file name " + src_path + " as " + files_path(src_path) +
                  " in theory " + seen_file.thy_name + " vs. " + thy.name)
            }

            val file_path = html_context.files_path(thy.name, src_path)
            val file_title = "File " + Symbol.cartouche_decoded(src_path.implode_short)
            HTML.write_document(file_path.dir, file_path.file_name,
              List(HTML.title(file_title)), List(html_context.head(file_title), file_html),
              base = Some(html_context.root_dir))

            List(HTML.link(files_path(src_path), HTML.text(file_title)))
          }

        val thy_title = "Theory " + thy.name.theory_base_name

        HTML.write_document(thy_dir, html_name(thy.name),
          List(HTML.title(thy_title)), List(html_context.head(thy_title), thy.html),
          base = Some(html_context.root_dir))

        if (thy_session.name == session) {
          Some(
            List(HTML.link(html_name(thy.name),
              HTML.text(thy.name.theory_base_name) :::
                (if (files.isEmpty) Nil else List(HTML.itemize(files))))))
        }
        else None
      }).flatten
    }

    val title = "Session " + session
    HTML.write_document(session_dir, "index.html",
      List(HTML.title(title + Isabelle_System.isabelle_heading())),
      html_context.head(title, List(HTML.par(view_links))) ::
        html_context.contents("Theories", theories),
      base = Some(html_context.root_dir))
  }
}
