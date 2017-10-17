/**
  * Copyright (C) 2017 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.oxf.fb

import org.orbeon.dom.QName
import org.orbeon.scaxon.SimplePath.Test

object XMLNames {

  val FBPrefix = "fb"
  val FB       = "http://orbeon.org/oxf/xml/form-builder"

  val FBMetadataTest    : Test  = FB → "metadata"
  val FBTemplateTest    : Test  = FB → "template"
  val FBTemplatesTest   : Test  = FB → "templates"
  val FBInstanceTest    : Test  = FB → "instance"
  val FBViewTest        : Test  = FB → "view"
  val FBDatatypeTest    : Test  = FB → "datatype"
  val FBBindTest        : Test  = FB → "bind"
  val FBEditorsTest     : Test  = FB → "editors"
  val FBDisplayNameTest : Test  = FB → "display-name"
  val FBIconTest        : Test  = FB → "icon"
  val FBSmallIconTest   : Test  = FB → "small-icon"

  val InitialIterations : QName = QName.get("initial-iterations", FBPrefix, FB)
}