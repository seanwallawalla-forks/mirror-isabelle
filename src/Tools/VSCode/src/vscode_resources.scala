/*  Title:      Tools/VSCode/src/vscode_resources.scala
    Author:     Makarius

Resources for VSCode Language Server, based on file-system URIs.
*/

package isabelle.vscode


import isabelle._

import java.net.{URI, URISyntaxException}
import java.io.{File => JFile}


object VSCode_Resources
{
  def is_wellformed(uri: String): Boolean =
    try { new JFile(new URI(uri)); true }
    catch { case _: URISyntaxException | _: IllegalArgumentException => false }

  def canonical_file(uri: String): JFile =
    new JFile(new URI(uri)).getCanonicalFile

  val empty: VSCode_Resources =
    new VSCode_Resources(Set.empty, Map.empty, Outer_Syntax.empty)
}

class VSCode_Resources(
    loaded_theories: Set[String],
    known_theories: Map[String, Document.Node.Name],
    base_syntax: Outer_Syntax)
  extends Resources(loaded_theories, known_theories, base_syntax)
{
  def node_name(uri: String): Document.Node.Name =
  {
    val file = VSCode_Resources.canonical_file(uri)  // FIXME wellformed!?
    val node = file.getPath
    val theory = Thy_Header.thy_name_bootstrap(node).getOrElse("")
    val master_dir = if (theory == "") "" else file.getParent
    Document.Node.Name(node, master_dir, theory)
  }
}