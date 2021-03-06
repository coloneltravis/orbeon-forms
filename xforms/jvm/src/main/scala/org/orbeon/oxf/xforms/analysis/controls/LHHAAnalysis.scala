/**
 *  Copyright (C) 2010 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis.controls

import org.orbeon.dom._
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.oxf.common.ValidationException
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util.{XPath, XPathCache}
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms.XFormsProperties._
import org.orbeon.oxf.xforms._
import org.orbeon.oxf.xforms.analysis._
import org.orbeon.oxf.xforms.analysis.model.ValidationLevel
import org.orbeon.oxf.xforms.analysis.model.ValidationLevel._
import org.orbeon.oxf.xforms.xbl.Scope
import org.orbeon.oxf.xml.Dom4j
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.orbeon.scaxon.SimplePath._
import org.orbeon.oxf.util.CoreUtils._

class LHHAAnalysis(
  staticStateContext : StaticStateContext,
  element            : Element,
  parent             : Option[ElementAnalysis],
  preceding          : Option[ElementAnalysis],
  scope              : Scope
) extends SimpleElementAnalysis(staticStateContext, element, parent, preceding, scope)
   with SingleNodeTrait
   with OptionalSingleNode
   with AppearanceTrait {

  self ⇒

  import LHHAAnalysis._

  require(parent.isDefined)

  def getParent = parent.get // TODO: rename `parent` to `parentOpt`, and this `def` to `parent`

  def lhhaType = LHHA.withName(localName)

  val forStaticIdOpt = element.attributeValueOpt(FOR_QNAME)
  val isLocal        = forStaticIdOpt.isEmpty
  val defaultToHTML  = LHHAAnalysis.isHTML(element) // IIUC: starting point for nested `<xf:output>`.
  val containsHTML   = LHHAAnalysis.containsHTML(element)

  // Updated in `attachToControl()`
  private var _isForRepeat                          : Boolean                                 = false
  private var _forRepeatNesting                     : Int                                     = 0
  private var _directTargetControlOpt               : Option[StaticLHHASupport]               = None
  private var _effectiveTargetControlOrPrefixedIdOpt: Option[StaticLHHASupport Either String] = None

  def isForRepeat                           = _isForRepeat
  def forRepeatNesting                      = _forRepeatNesting
  def directTargetControl                   = _directTargetControlOpt getOrElse (throw new IllegalStateException)
  def effectiveTargetControlOrPrefixedIdOpt = _effectiveTargetControlOrPrefixedIdOpt

  val hasLocalMinimalAppearance = appearances(XFORMS_MINIMAL_APPEARANCE_QNAME) || appearances(XXFORMS_PLACEHOLDER_APPEARANCE_QNAME)
  val hasLocalFullAppearance    = appearances(XFORMS_FULL_APPEARANCE_QNAME)

  // What we support for alert level/validation:
  //
  // - <xf:alert>                                  → alert applies to all alert levels
  // - <xf:alert level="foo">                      → same, unknown level is ignored [SHOULD WARN]
  // - <xf:alert level="warning info">             → alert only applies to warning and info levels
  // - <xf:alert level="warning" validation="">    → same, blank attribute is same as missing attribute [SHOULD WARN]
  // - <xf:alert validation="c1 c2">               → alert only applies if either validation c1 or c2 fails
  // - <xf:alert level="" validation="c1 c2">      → same, blank attribute is same as missing attribute [SHOULD WARN]
  // - <xf:alert level="error" validation="c1 c2"> → same, level is ignored when a validation is present [SHOULD WARN]

  val forValidations =
    if (localName == "alert")
      gatherAlertValidations(Option(element.attributeValue(VALIDATION_QNAME)))
    else
      Set.empty[String]

  val forLevels =
    if (localName == "alert")
      gatherAlertLevels(Option(element.attributeValue(LEVEL_QNAME)))
    else
      Set.empty[ValidationLevel]

  // Attach this LHHA to its target control if any
  def attachToControl(): Unit = {

    val (targetControl, effectiveTargetControlOrPrefixedIdOpt) = {

      def searchLHHAControlInScope(scope: Scope, forStaticId: String): Option[StaticLHHASupport] =
        part.findControlAnalysis(scope.prefixedIdForStaticId(forStaticId)) collect { case e: StaticLHHASupport ⇒ e}

      def searchXblLabelFor(e: StaticLHHASupport): Option[StaticLHHASupport Either String] =
        e match {
          case xbl: ComponentControl ⇒
            xbl.binding.abstractBinding.labelFor match {
              case Some(nestedLabelForStaticId) ⇒
                searchLHHAControlInScope(xbl.binding.innerScope, nestedLabelForStaticId) match {
                  case Some(nestedLabelForTarget) ⇒ searchXblLabelFor(nestedLabelForTarget) // recurse
                  case None                       ⇒ Some(Right(xbl.binding.innerScope.fullPrefix + nestedLabelForStaticId)) // assuming id of an HTML element
                }
              case None ⇒
                Some(Left(xbl))
            }
          case _ ⇒
            Some(Left(e))
        }

      def initialElemFromForOpt =
        forStaticIdOpt map  { forStaticId ⇒
          searchLHHAControlInScope(scope, forStaticId) getOrElse (
            throw new ValidationException(
              s"`for` attribute with value `$forStaticId` doesn't point to a control supporting label, help, hint or alert.",
              ElementAnalysis.createLocationData(element)
            )
          )
        }

      val initialElem = initialElemFromForOpt getOrElse {
        getParent match {
          case e: StaticLHHASupport ⇒ e
          case _ ⇒
            throw new ValidationException(
              s"parent control must support label, help, hint or alert.",
              ElementAnalysis.createLocationData(element)
            )
        }
      }

      (initialElem, searchXblLabelFor(initialElem))
    }

    // NOTE: We don't support a reference to an effective control within an XBL which is in a repeat nested within the XBL!
    val repeatNesting = targetControl.ancestorRepeats.size - self.ancestorRepeats.size

    self._isForRepeat                           = ! self.isLocal && repeatNesting > 0
    self._forRepeatNesting                      = if (self._isForRepeat && repeatNesting > 0) repeatNesting else 0
    self._directTargetControlOpt                = Some(targetControl)
    self._effectiveTargetControlOrPrefixedIdOpt = effectiveTargetControlOrPrefixedIdOpt

    // We attach the LHHA to one, and possibly two target controls
    targetControl.attachLHHA(self)
    effectiveTargetControlOrPrefixedIdOpt foreach {
      _.left.toOption filter (_ ne targetControl) foreach (_.attachLHHABy(self))
    }
  }

  // TODO: make use of static value
  //
  // - output static value in HTML markup and repeat templates
  // - if has static value, don't attempt to compare values upon diff, and never send new related information to client
  // - 2017-10-17: Now using this in `XFormsLHHAControl`.
  //
  // TODO: figure out whether to allow HTML or not (could default to true?)
  //
  val staticValue =
    LHHAAnalysis.hasStaticValue(staticStateContext, element) option
      XFormsUtils.getStaticChildElementValue(containerScope.fullPrefix, element, true, null)

  def debugOut(): Unit =
    if (staticValue.isDefined)
      println("static value for control " + prefixedId + " ⇒ " + staticValue.get)

  // Consider that LHHA don't have context/binding as we delegate implementation in computeValueAnalysis
  override protected def computeContextAnalysis = None
  override protected def computeBindingAnalysis = None

  override protected def computeValueAnalysis = {
    if (staticValue.isEmpty) {
      // Value is likely not static

      // Delegate to concrete implementation
      val delegateAnalysis =
        new SimpleElementAnalysis(staticStateContext, element, parent, preceding, scope)
          with ValueTrait with OptionalSingleNode with ViewTrait

      delegateAnalysis.analyzeXPath()

      if (ref.isDefined || value.isDefined) {
        // 1. E.g. <xf:label model="…" context="…" value|ref="…"/>

        // Don't assert because we want to support nested `fr:param` elements in Form Builder.
        //assert(element.elements.isEmpty) // no children elements allowed in this case

        // Use value provided by the delegate
        delegateAnalysis.getValueAnalysis
      } else {
        // 2. E.g. <xf:label>…<xf:output value|ref=""…/>…<span class="{…}">…</span></xf:label>

        // NOTE: We do allow @context and/or @model on LHHA element, which can change the context

        // The subtree can only contain HTML elements interspersed with xf:output. HTML elements may have AVTs.
        var combinedAnalysis: XPathAnalysis = StringAnalysis()

        Dom4jUtils.visitSubtree(element, new Dom4jUtils.VisitorListener {
          val hostLanguageAVTs = XFormsProperties.isHostLanguageAVTs
          def startElement(element: Element): Unit = {
            if (element.getQName == XFORMS_OUTPUT_QNAME) {
              // Add dependencies
              val outputAnalysis =
                new SimpleElementAnalysis(
                  staticStateContext = staticStateContext,
                  element            = element,
                  parent             = Some(delegateAnalysis),
                  preceding          = None,
                  scope              = delegateAnalysis.getChildElementScope(element)
                ) with ValueTrait with OptionalSingleNode with ViewTrait
              outputAnalysis.analyzeXPath()
              if (outputAnalysis.getValueAnalysis.isDefined)
                combinedAnalysis = combinedAnalysis combine outputAnalysis.getValueAnalysis.get
            } else if (hostLanguageAVTs) {
              for {
                attribute ← Dom4j.attributes(element)
                attributeValue = attribute.getValue
                if XFormsUtils.maybeAVT(attributeValue)
              } combinedAnalysis = NegativeAnalysis(attributeValue) // not supported just yet
            }
          }

          def endElement(element: Element) = ()
          def text(text: Text) = ()
        })

        // Result of all combined analyses
        Some(combinedAnalysis)
      }
    } else
      // Value of LHHA is 100% static and analysis is constant
      Some(StringAnalysis())
  }
}

object LHHAAnalysis {

  def isHTML(e: Element)      = e.attributeValue(MEDIATYPE_QNAME) == "text/html"
  def isPlainText(e: Element) = e.attributeValue(MEDIATYPE_QNAME) == "text/plain"

  private def containsHTML(lhhaElement: Element) = {

    val lhhaElem =
      new DocumentWrapper(
          lhhaElement.getDocument,
          null,
          XPath.GlobalConfiguration
        ).wrap(lhhaElement)

    val XFOutput = URIQualifiedName(XFORMS_NAMESPACE_URI, "output")

    val descendantOtherElems = lhhaElem descendant * filter (_.uriQualifiedName != XFOutput)
    val descendantOutputs    = lhhaElem descendant XFOutput

    isHTML(lhhaElement) || descendantOtherElems.nonEmpty || (descendantOutputs exists {
      _.attValueOpt("mediatype") contains "text/html"
    })
  }

  // Try to figure out if we have a dynamic LHHA element, including nested xf:output and AVTs.
  private def hasStaticValue(staticStateContext: StaticStateContext, lhhaElement: Element): Boolean = {

    val SearchExpression =
      """
        not(
        exists(
          descendant-or-self::xf:*[@ref or @nodeset or @bind or @value] |
          descendant::*[@*[contains(., '{')]]
        )
        )
      """

    XPathCache.evaluateSingle(
      contextItem        = new DocumentWrapper(
        lhhaElement.getDocument,
        null,
        XPath.GlobalConfiguration
      ).wrap(lhhaElement),
      xpathString        = SearchExpression,
      namespaceMapping   = XFormsStaticStateImpl.BASIC_NAMESPACE_MAPPING,
      variableToValueMap = null,
      functionLibrary    = null,
      functionContext    = null,
      baseURI            = null,
      locationData       = ElementAnalysis.createLocationData(lhhaElement),
      reporter           = null
    ).asInstanceOf[Boolean]
  }

  // Whether the control has a placeholder for the given LHHA type
  // TODO: This should be computed statically.
  def hasLHHAPlaceholder(elementAnalysis: ElementAnalysis, lhhaType: LHHA): Boolean =
    elementAnalysis match {
      case lhhaTrait: StaticLHHASupport ⇒
        lhhaTrait.lhh(lhhaType) match {
          case Some(labelOrHint) ⇒
            labelOrHint.hasLocalMinimalAppearance || (
              ! labelOrHint.hasLocalFullAppearance &&
              stringToSet(
                elementAnalysis.part.staticState.staticStringProperty(
                  if (lhhaType == LHHA.Hint) HINT_APPEARANCE_PROPERTY else LABEL_APPEARANCE_PROPERTY
                )
              )(XFORMS_MINIMAL_APPEARANCE_QNAME.name)
          )
          case None ⇒
            false
        }
      case _ ⇒
        false
    }

  def gatherAlertValidations(validationAtt: Option[String]) =
    stringOptionToSet(validationAtt)

  def gatherAlertLevels(levelAtt: Option[String]) =
    stringOptionToSet(levelAtt) collect LevelByName
}