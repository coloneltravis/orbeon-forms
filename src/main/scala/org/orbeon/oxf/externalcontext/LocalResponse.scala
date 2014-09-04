/**
 * Copyright (C) 2014 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.externalcontext

import java.io._

import org.orbeon.oxf.http.{EmptyInputStream, Headers}
import org.orbeon.oxf.pipeline.api.ExternalContext.Response
import org.orbeon.oxf.util.StringBuilderWriter

import scala.collection.mutable


class LocalResponse(rewriter: URLRewriter) extends Response {

    private var _statusCode  = 200
    private var _lowerCaseHeaders = mutable.LinkedHashMap[String, List[String]]()

    private var _stringWriter: StringBuilderWriter        = null
    private var _printWriter : PrintWriter                = null
    private var _byteStream  : LocalByteArrayOutputStream = null
    private var _inputStream : InputStream                = null

    def statusCode = _statusCode

    def capitalizedHeaders =
        _lowerCaseHeaders.toList map
        { case (k, v) ⇒ Headers.capitalizeCommonOrSplitHeader(k) → v } toMap

    def getInputStream: InputStream = {
        if (_inputStream eq null) {
            _inputStream =
                if (_stringWriter ne null) {
                    val bytes = _stringWriter.getBuilder.toString.getBytes("UTF-8")
                    new ByteArrayInputStream(bytes, 0, bytes.length)
                } else if (_byteStream ne null) {
                    new ByteArrayInputStream(_byteStream.getByteArray, 0, _byteStream.size)
                }  else {
                    EmptyInputStream
                }
        }
        _inputStream
    }

    def setHeader(name: String, value: String): Unit =
        _lowerCaseHeaders += name.toLowerCase → List(value)

    def addHeader(name: String, value: String): Unit =
        _lowerCaseHeaders += name.toLowerCase  → (_lowerCaseHeaders.getOrElse(name.toLowerCase , Nil) :+ value)

    def checkIfModifiedSince(lastModified: Long) = true

    def getCharacterEncoding = null

    def getNamespacePrefix = null

    def getOutputStream: OutputStream = {
        if (_byteStream eq null)
            _byteStream = new LocalByteArrayOutputStream
        _byteStream
    }

    def getWriter: PrintWriter = {
        if (_stringWriter eq null) {
            _stringWriter = new StringBuilderWriter
            _printWriter = new PrintWriter(_stringWriter)
        }
        _printWriter
    }

    def isCommitted = false

    def reset() = ()

    def rewriteActionURL(urlString: String) =
        rewriter.rewriteActionURL(urlString)

    def rewriteRenderURL(urlString: String) =
        rewriter.rewriteRenderURL(urlString)

    def rewriteActionURL(urlString: String, portletMode: String, windowState: String) =
        rewriter.rewriteActionURL(urlString, portletMode, windowState)

    def rewriteRenderURL(urlString: String, portletMode: String, windowState: String) =
        rewriter.rewriteRenderURL(urlString, portletMode, windowState)

    def rewriteResourceURL(urlString: String, rewriteMode: Int) =
        rewriter.rewriteResourceURL(urlString, rewriteMode)

    def sendError(sc: Int): Unit =
        this._statusCode = sc

    def sendRedirect(location: String, isServerSide: Boolean, isExitPortal: Boolean) = () // NIY

    def setPageCaching(lastModified: Long) = () // Q: Should set headers?
    def setResourceCaching(lastModified: Long, expires: Long) = () // Q: Should set headers?

    def setStatus(status: Int): Unit =
        this._statusCode = status

    def setContentLength(len: Int): Unit =
        setHeader(Headers.ContentLength, len.toString)

    def setContentType(contentType: String): Unit =
        setHeader(Headers.ContentType, contentType)

    def setTitle(title: String) = ()

    def getNativeResponse: AnyRef =
        throw new UnsupportedOperationException
}

private class LocalByteArrayOutputStream extends ByteArrayOutputStream {
    def getByteArray = buf
}
