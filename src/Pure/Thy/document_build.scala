/*  Title:      Pure/Thy/document_build.scala
    Author:     Makarius

Build theory document (PDF) from session database.
*/

package isabelle


object Document_Build
{
  /* document variants */

  object Content
  {
    def apply(path: Path, content: Bytes): Content = new Content_Bytes(path, content)
    def apply(path: Path, content: String): Content = new Content_String(path, content)
  }
  trait Content
  {
    def write(dir: Path): Unit
  }
  final class Content_Bytes private[Document_Build](path: Path, content: Bytes) extends Content
  {
    def write(dir: Path): Unit = Bytes.write(dir + path, content)
  }
  final class Content_String private[Document_Build](path: Path, content: String) extends Content
  {
    def write(dir: Path): Unit = File.write(dir + path, content)
  }

  abstract class Document_Name
  {
    def name: String
    def path: Path = Path.basic(name)

    override def toString: String = name
  }

  object Document_Variant
  {
    def parse(name: String, tags: String): Document_Variant =
      Document_Variant(name, Library.space_explode(',', tags))

    def parse(opt: String): Document_Variant =
      Library.space_explode('=', opt) match {
        case List(name) => Document_Variant(name, Nil)
        case List(name, tags) => parse(name, tags)
        case _ => error("Malformed document variant: " + quote(opt))
      }
  }

  sealed case class Document_Variant(name: String, tags: List[String]) extends Document_Name
  {
    def print_tags: String = tags.mkString(",")
    def print: String = if (tags.isEmpty) name else name + "=" + print_tags

    def isabelletags: Content =
    {
      val path = Path.explode("isabelletags.sty")
      val content =
        Library.terminate_lines(
          tags.map(tag =>
            tag.toList match {
              case '/' :: cs => "\\isafoldtag{" + cs.mkString + "}"
              case '-' :: cs => "\\isadroptag{" + cs.mkString + "}"
              case '+' :: cs => "\\isakeeptag{" + cs.mkString + "}"
              case cs => "\\isakeeptag{" + cs.mkString + "}"
            }))
      Content(path, content)
    }
  }

  sealed case class Document_Input(name: String, sources: SHA1.Digest)
    extends Document_Name

  sealed case class Document_Output(name: String, sources: SHA1.Digest, log_xz: Bytes, pdf: Bytes)
    extends Document_Name
  {
    def log: String = log_xz.uncompress().text
    def log_lines: List[String] = split_lines(log)

    def write(db: SQL.Database, session_name: String): Unit =
      write_document(db, session_name, this)

    def write(dir: Path): Path =
    {
      val path = dir + Path.basic(name).pdf
      Isabelle_System.make_directory(path.expand.dir)
      Bytes.write(path, pdf)
      path
    }
  }


  /* SQL data model */

  object Data
  {
    val session_name = SQL.Column.string("session_name").make_primary_key
    val name = SQL.Column.string("name").make_primary_key
    val sources = SQL.Column.string("sources")
    val log_xz = SQL.Column.bytes("log_xz")
    val pdf = SQL.Column.bytes("pdf")

    val table = SQL.Table("isabelle_documents", List(session_name, name, sources, log_xz, pdf))

    def where_equal(session_name: String, name: String = ""): SQL.Source =
      "WHERE " + Data.session_name.equal(session_name) +
        (if (name == "") "" else " AND " + Data.name.equal(name))
  }

  def read_documents(db: SQL.Database, session_name: String): List[Document_Input] =
  {
    val select = Data.table.select(List(Data.name, Data.sources), Data.where_equal(session_name))
    db.using_statement(select)(stmt =>
      stmt.execute_query().iterator(res =>
      {
        val name = res.string(Data.name)
        val sources = res.string(Data.sources)
        Document_Input(name, SHA1.fake(sources))
      }).toList)
  }

  def read_document(db: SQL.Database, session_name: String, name: String): Option[Document_Output] =
  {
    val select = Data.table.select(sql = Data.where_equal(session_name, name))
    db.using_statement(select)(stmt =>
    {
      val res = stmt.execute_query()
      if (res.next()) {
        val name = res.string(Data.name)
        val sources = res.string(Data.sources)
        val log_xz = res.bytes(Data.log_xz)
        val pdf = res.bytes(Data.pdf)
        Some(Document_Output(name, SHA1.fake(sources), log_xz, pdf))
      }
      else None
    })
  }

  def write_document(db: SQL.Database, session_name: String, doc: Document_Output): Unit =
  {
    db.using_statement(Data.table.insert())(stmt =>
    {
      stmt.string(1) = session_name
      stmt.string(2) = doc.name
      stmt.string(3) = doc.sources.toString
      stmt.bytes(4) = doc.log_xz
      stmt.bytes(5) = doc.pdf
      stmt.execute()
    })
  }


  /* context */

  val isabelle_styles: List[Path] =
    List("comment.sty", "isabelle.sty", "isabellesym.sty", "pdfsetup.sty", "railsetup.sty").
      map(name => Path.explode("~~/lib/texinputs") + Path.basic(name))

  def context(
    session: String,
    deps: Sessions.Deps,
    db_context: Sessions.Database_Context,
    progress: Progress = new Progress): Context =
  {
    val info = deps.sessions_structure(session)
    val base = deps(session)
    val hierarchy = deps.sessions_structure.hierarchy(session)
    new Context(info, base, hierarchy, db_context, progress)
  }

  final class Context private[Document_Build](
    info: Sessions.Info,
    base: Sessions.Base,
    hierarchy: List[String],
    db_context: Sessions.Database_Context,
    val progress: Progress = new Progress)
  {
    /* session info */

    def session: String = info.name
    def options: Options = info.options

    def document_bibliography: Boolean = options.bool("document_bibliography")

    def document_logo: Option[String] =
      options.string("document_logo") match {
        case "" => None
        case "_" => Some("")
        case name => Some(name)
      }

    def document_build: String = options.string("document_build")

    def get_engine(): Engine =
    {
      val name = document_build
      engines.find(_.name == name).getOrElse(error("Bad document_build engine " + quote(name)))
    }

    def get_export(theory: String, name: String): Export.Entry =
      db_context.get_export(hierarchy, theory, name)


    /* document content */

    def documents: List[Document_Variant] = info.documents

    def session_theories: List[Document.Node.Name] = base.session_theories
    def document_theories: List[Document.Node.Name] = session_theories ::: base.document_theories

    lazy val tex_files: List[Content] =
      for (name <- document_theories)
      yield {
        val path = Path.basic(tex_name(name))
        val xml = YXML.parse_body(get_export(name.theory, Export.DOCUMENT_LATEX).text)
        val content = Latex.output(xml, file_pos = name.path.implode_symbolic)
        Content(path, content)
      }

    lazy val session_graph: Content =
    {
      val path = Presentation.session_graph_path
      val content = graphview.Graph_File.make_pdf(options, base.session_graph_display)
      Content(path, content)
    }

    lazy val session_tex: Content =
    {
      val path = Path.basic("session.tex")
      val content =
        Library.terminate_lines(
          base.session_theories.map(name => "\\input{" + tex_name(name) + "}"))
      Content(path, content)
    }

    lazy val isabelle_logo: Option[Content] =
    {
      document_logo.map(logo_name =>
        Isabelle_System.with_tmp_file("logo", ext = "pdf")(tmp_path =>
        {
          Logo.create_logo(logo_name, output_file = tmp_path, quiet = true)
          val path = Path.basic("isabelle_logo.pdf")
          val content = Bytes.read(tmp_path)
          Content(path, content)
        }))
    }


    /* document directory */

    def prepare_directory(dir: Path, doc: Document_Variant): Directory =
    {
      val doc_dir = Isabelle_System.make_directory(dir + Path.basic(doc.name))


      /* actual sources: with SHA1 digest */

      isabelle_styles.foreach(Isabelle_System.copy_file(_, doc_dir))
      doc.isabelletags.write(doc_dir)

      for ((base_dir, src) <- info.document_files) {
        Isabelle_System.copy_file_base(info.dir + base_dir, src, doc_dir)
      }

      session_tex.write(doc_dir)
      tex_files.foreach(_.write(doc_dir))

      val root_name1 = "root_" + doc.name
      val root_name = if ((doc_dir + Path.explode(root_name1).tex).is_file) root_name1 else "root"

      val digests1 = List(doc.print, document_logo.toString, document_build).map(SHA1.digest)
      val digests2 = File.find_files(doc_dir.file, follow_links = true).map(SHA1.digest)
      val sources = SHA1.digest_set(digests1 ::: digests2)


      /* derived material: without SHA1 digest */

      isabelle_logo.foreach(_.write(doc_dir))

      session_graph.write(doc_dir)

      Directory(doc_dir, doc, root_name, sources)
    }

    def old_document(directory: Directory): Option[Document_Output] =
      for {
        old_doc <- db_context.input_database(session)(read_document(_, _, directory.doc.name))
        if old_doc.sources == directory.sources
      }
      yield old_doc
  }

  sealed case class Directory(
    doc_dir: Path,
    doc: Document_Variant,
    root_name: String,
    sources: SHA1.Digest)
  {
    def root_name_script(ext: String = ""): String =
      Bash.string(if (ext.isEmpty) root_name else root_name + "." + ext)

    def conditional_script(ext: String, exe: String, after: String = ""): String =
      "if [ -f " + root_name_script(ext) + " ]\n" +
      "then\n" +
      "  " + exe + " " + root_name_script() + "\n" +
      (if (after.isEmpty) "" else "  " + after) +
      "fi\n"

    def log_errors(): List[String] =
      Latex.latex_errors(doc_dir, root_name) :::
      Bibtex.bibtex_errors(doc_dir, root_name)

    def make_document(log: List[String], errors: List[String]): Document_Output =
    {
      val root_pdf = Path.basic(root_name).pdf
      val result_pdf = doc_dir + root_pdf

      if (errors.nonEmpty) {
        val errors1 = errors ::: List("Failed to build document " + quote(doc.name))
        throw new Build_Error(log, Exn.cat_message(errors1: _*))
      }
      else if (!result_pdf.is_file) {
        val message = "Bad document result: expected to find " + root_pdf
        throw new Build_Error(log, message)
      }
      else {
        val log_xz = Bytes(cat_lines(log)).compress()
        val pdf = Bytes.read(result_pdf)
        Document_Output(doc.name, sources, log_xz, pdf)
      }
    }
  }


  /* build engines */

  lazy val engines: List[Engine] = Isabelle_System.make_services(classOf[Engine])

  abstract class Engine(val name: String) extends Isabelle_System.Service
  {
    override def toString: String = name

    def prepare_directory(context: Context, dir: Path, doc: Document_Variant): Directory
    def build_document(context: Context, directory: Directory, verbose: Boolean): Document_Output
  }

  abstract class Bash_Engine(name: String) extends Engine(name)
  {
    def prepare_directory(context: Context, dir: Path, doc: Document_Variant): Directory =
      context.prepare_directory(dir, doc)

    def use_pdflatex: Boolean = false
    def latex_script(context: Context, directory: Directory): String =
      (if (use_pdflatex) "$ISABELLE_PDFLATEX" else "$ISABELLE_LUALATEX") +
        " " + directory.root_name_script() + "\n"

    def bibtex_script(context: Context, directory: Directory, latex: Boolean = false): String =
    {
      val ext = if (context.document_bibliography) "aux" else "bib"
      directory.conditional_script(ext, "$ISABELLE_BIBTEX",
        after = if (latex) latex_script(context, directory) else "")
    }

    def makeindex_script(context: Context, directory: Directory, latex: Boolean = false): String =
      directory.conditional_script("idx", "$ISABELLE_MAKEINDEX",
        after = if (latex) latex_script(context, directory) else "")

    def use_build_script: Boolean = false
    def build_script(context: Context, directory: Directory): String =
    {
      val has_build_script = (directory.doc_dir + Path.explode("build")).is_file

      if (!use_build_script && has_build_script) {
        error("Unexpected document build script for option document_build=" +
          quote(context.document_build))
      }
      else if (use_build_script && !has_build_script) error("Missing document build script")
      else if (has_build_script) "./build pdf " + Bash.string(directory.doc.name)
      else {
        "set -e\n" +
        latex_script(context, directory) +
        bibtex_script(context, directory, latex = true) +
        makeindex_script(context, directory) +
        latex_script(context, directory) +
        makeindex_script(context, directory, latex = true)
      }
    }

    def build_document(context: Context, directory: Directory, verbose: Boolean): Document_Output =
    {
      val result =
        context.progress.bash(
          build_script(context, directory),
          cwd = directory.doc_dir.file,
          echo = verbose,
          watchdog = Time.seconds(0.5))

      val log = result.out_lines ::: result.err_lines
      val errors = (if (result.ok) Nil else List(result.err)) ::: directory.log_errors()
      directory.make_document(log, errors)
    }
  }

  class LuaLaTeX_Engine extends Bash_Engine("lualatex")
  class PDFLaTeX_Engine extends Bash_Engine("pdflatex") { override def use_pdflatex: Boolean = true }
  class Build_Engine extends Bash_Engine("build") { override def use_build_script: Boolean = true }


  /* build documents */

  def tex_name(name: Document.Node.Name): String = name.theory_base_name + ".tex"

  class Build_Error(val log_lines: List[String], val message: String)
    extends Exn.User_Error(message)

  def build_documents(
    context: Context,
    output_sources: Option[Path] = None,
    output_pdf: Option[Path] = None,
    verbose: Boolean = false): List[Document_Output] =
  {
    val progress = context.progress
    val engine = context.get_engine()

    val documents =
      for (doc <- context.documents)
      yield {
        Isabelle_System.with_tmp_dir("document")(tmp_dir =>
        {
          progress.echo("Preparing " + context.session + "/" + doc.name + " ...")
          val start = Time.now()

          output_sources.foreach(engine.prepare_directory(context, _, doc))
          val directory = engine.prepare_directory(context, tmp_dir, doc)

          val document =
            context.old_document(directory) getOrElse
              engine.build_document(context, directory, verbose)

          val stop = Time.now()
          val timing = stop - start

          progress.echo("Finished " + context.session + "/" + doc.name +
            " (" + timing.message_hms + " elapsed time)")

          document
        })
      }

    for (dir <- output_pdf; doc <- documents) {
      val path = doc.write(dir)
      progress.echo("Document at " + path.absolute)
    }

    documents
  }


  /* Isabelle tool wrapper */

  val isabelle_tool =
    Isabelle_Tool("document", "prepare session theory document", Scala_Project.here, args =>
    {
      var output_sources: Option[Path] = None
      var output_pdf: Option[Path] = None
      var verbose_latex = false
      var dirs: List[Path] = Nil
      var options = Options.init()
      var verbose_build = false

      val getopts = Getopts(
        """
Usage: isabelle document [OPTIONS] SESSION

  Options are:
    -O DIR       output directory for LaTeX sources and resulting PDF
    -P DIR       output directory for resulting PDF
    -S DIR       output directory for LaTeX sources
    -V           verbose latex
    -d DIR       include session directory
    -o OPTION    override Isabelle system OPTION (via NAME=VAL or NAME)
    -v           verbose build

  Prepare the theory document of a session.
""",
        "O:" -> (arg =>
          {
            val dir = Path.explode(arg)
            output_sources = Some(dir)
            output_pdf = Some(dir)
          }),
        "P:" -> (arg => { output_pdf = Some(Path.explode(arg)) }),
        "S:" -> (arg => { output_sources = Some(Path.explode(arg)) }),
        "V" -> (_ => verbose_latex = true),
        "d:" -> (arg => dirs = dirs ::: List(Path.explode(arg))),
        "o:" -> (arg => options = options + arg),
        "v" -> (_ => verbose_build = true))

      val more_args = getopts(args)
      val session =
        more_args match {
          case List(a) => a
          case _ => getopts.usage()
        }

      val progress = new Console_Progress(verbose = verbose_build)
      val store = Sessions.store(options)

      progress.interrupt_handler {
        val res =
          Build.build(options, selection = Sessions.Selection.session(session),
            dirs = dirs, progress = progress, verbose = verbose_build)
        if (!res.ok) error("Failed to build session " + quote(session))

        val deps =
          Sessions.load_structure(options + "document=pdf", dirs = dirs).
            selection_deps(Sessions.Selection.session(session))

        if (output_sources.isEmpty && output_pdf.isEmpty) {
          progress.echo_warning("No output directory")
        }

        using(store.open_database_context())(db_context =>
        {
          build_documents(context(session, deps, db_context, progress = progress),
            output_sources = output_sources, output_pdf = output_pdf,
            verbose = verbose_latex)
        })
      }
    })
}
