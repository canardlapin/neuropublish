package neuropublish.frontend

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** The Three.js namespace (`import * as THREE from "three"`), resolved by Vite from the frontend's
  * `three` dependency. ScalaFIM's `ThreeJsRuntime` takes it as a dynamic namespace object so no
  * Three.js type escapes into Scala.
  */
@js.native
@JSImport("three", JSImport.Namespace)
object ThreeModule extends js.Object

object Three:
  def namespace: js.Dynamic = ThreeModule.asInstanceOf[js.Dynamic]
