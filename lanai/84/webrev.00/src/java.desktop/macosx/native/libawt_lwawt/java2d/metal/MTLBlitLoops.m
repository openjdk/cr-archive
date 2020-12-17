/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#ifndef HEADLESS

#include <jni.h>
#include <jlong.h>

#include "SurfaceData.h"
#include "MTLBlitLoops.h"
#include "MTLRenderQueue.h"
#include "MTLSurfaceData.h"
#include "MTLUtils.h"
#include "GraphicsPrimitiveMgr.h"

#include <stdlib.h> // malloc
#include <string.h> // memcpy
#include "IntArgbPre.h"

#import <Accelerate/Accelerate.h>

#ifdef DEBUG
#define TRACE_ISOBLIT
#define TRACE_BLIT
#endif //DEBUG
//#define DEBUG_ISOBLIT
//#define DEBUG_BLIT

typedef struct {
    MTLPixelFormat   format;
    jboolean hasAlpha;
    jboolean isPremult;
    const uint8_t * permuteMap;
} MTLRasterFormatInfo;

// 0 denotes the alpha channel, 1 the red channel, 2 the green channel, and 3 the blue channel.
const uint8_t permuteMap_rgbx[4] = { 1, 2, 3, 0 };
const uint8_t permuteMap_bgrx[4] = { 3, 2, 1, 0 };

static uint8_t revertPerm(const uint8_t * perm, uint8_t pos) {
    for (int c = 0; c < 4; ++c) {
        if (perm[c] == pos)
            return c;
    }
    return -1;
}

#define uint2swizzle(channel) (channel == 0 ? MTLTextureSwizzleAlpha : (channel == 1 ? MTLTextureSwizzleRed : (channel == 2 ? MTLTextureSwizzleGreen : (channel == 3 ? MTLTextureSwizzleBlue : MTLTextureSwizzleZero))))

/**
 * This table contains the "pixel formats" for all system memory surfaces
 * that Metal is capable of handling, indexed by the "PF_" constants defined
 * in MTLLSurfaceData.java.  These pixel formats contain information that is
 * passed to Metal when copying from a system memory ("Sw") surface to
 * an Metal surface
 */
MTLRasterFormatInfo RasterFormatInfos[] = {
        { MTLPixelFormatBGRA8Unorm, 1, 0, NULL }, /* 0 - IntArgb      */ // Argb (in java notation)
        { MTLPixelFormatBGRA8Unorm, 1, 1, NULL }, /* 1 - IntArgbPre   */
        { MTLPixelFormatBGRA8Unorm, 0, 1, NULL }, /* 2 - IntRgb       */ // xrgb
        { MTLPixelFormatBGRA8Unorm, 0, 1, permuteMap_rgbx }, /* 3 - IntRgbx      */
        { MTLPixelFormatRGBA8Unorm, 0, 1, NULL }, /* 4 - IntBgr       */ // xbgr
        { MTLPixelFormatBGRA8Unorm, 0, 1, permuteMap_bgrx }, /* 5 - IntBgrx      */

//        TODO: support 2-byte formats
//        { GL_BGRA, GL_UNSIGNED_SHORT_1_5_5_5_REV,
//                2, 0, 1,                                     }, /* 7 - Ushort555Rgb */
//        { GL_RGBA, GL_UNSIGNED_SHORT_5_5_5_1,
//                2, 0, 1,                                     }, /* 8 - Ushort555Rgbx*/
//        { GL_LUMINANCE, GL_UNSIGNED_BYTE,
//                1, 0, 1,                                     }, /* 9 - ByteGray     */
//        { GL_LUMINANCE, GL_UNSIGNED_SHORT,
//                2, 0, 1,                                     }, /*10 - UshortGray   */
//        { GL_BGR,  GL_UNSIGNED_BYTE,
//                1, 0, 1,                                     }, /*11 - ThreeByteBgr */
};

extern void J2dTraceImpl(int level, jboolean cr, const char *string, ...);

void fillTxQuad(
        struct TxtVertex * txQuadVerts,
        jint sx1, jint sy1, jint sx2, jint sy2, jint sw, jint sh,
        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2, jdouble dw, jdouble dh
) {
    const float nsx1 = sx1/(float)sw;
    const float nsy1 = sy1/(float)sh;
    const float nsx2 = sx2/(float)sw;
    const float nsy2 = sy2/(float)sh;

    txQuadVerts[0].position[0] = dx1;
    txQuadVerts[0].position[1] = dy1;
    txQuadVerts[0].txtpos[0]   = nsx1;
    txQuadVerts[0].txtpos[1]   = nsy1;

    txQuadVerts[1].position[0] = dx2;
    txQuadVerts[1].position[1] = dy1;
    txQuadVerts[1].txtpos[0]   = nsx2;
    txQuadVerts[1].txtpos[1]   = nsy1;

    txQuadVerts[2].position[0] = dx2;
    txQuadVerts[2].position[1] = dy2;
    txQuadVerts[2].txtpos[0]   = nsx2;
    txQuadVerts[2].txtpos[1]   = nsy2;

    txQuadVerts[3].position[0] = dx2;
    txQuadVerts[3].position[1] = dy2;
    txQuadVerts[3].txtpos[0]   = nsx2;
    txQuadVerts[3].txtpos[1]   = nsy2;

    txQuadVerts[4].position[0] = dx1;
    txQuadVerts[4].position[1] = dy2;
    txQuadVerts[4].txtpos[0]   = nsx1;
    txQuadVerts[4].txtpos[1]   = nsy2;

    txQuadVerts[5].position[0] = dx1;
    txQuadVerts[5].position[1] = dy1;
    txQuadVerts[5].txtpos[0]   = nsx1;
    txQuadVerts[5].txtpos[1]   = nsy1;
}

//#define TRACE_drawTex2Tex

void drawTex2Tex(MTLContext *mtlc,
                        id<MTLTexture> src, id<MTLTexture> dst,
                        jboolean isSrcOpaque, jboolean isDstOpaque, jint hint,
                        jint sx1, jint sy1, jint sx2, jint sy2,
                        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
#ifdef TRACE_drawTex2Tex
    J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "drawTex2Tex: src tex=%p, dst tex=%p", src, dst);
    J2dRlsTraceLn4(J2D_TRACE_VERBOSE, "  sw=%d sh=%d dw=%d dh=%d", src.width, src.height, dst.width, dst.height);
    J2dRlsTraceLn4(J2D_TRACE_VERBOSE, "  sx1=%d sy1=%d sx2=%d sy2=%d", sx1, sy1, sx2, sy2);
    J2dRlsTraceLn4(J2D_TRACE_VERBOSE, "  dx1=%f dy1=%f dx2=%f dy2=%f", dx1, dy1, dx2, dy2);
#endif //TRACE_drawTex2Tex

    id<MTLRenderCommandEncoder> encoder = [mtlc.encoderManager getTextureEncoder:dst
                                                                     isSrcOpaque:isSrcOpaque
                                                                     isDstOpaque:isDstOpaque
                                                                   interpolation:hint
    ];

    struct TxtVertex quadTxVerticesBuffer[6];
    fillTxQuad(quadTxVerticesBuffer, sx1, sy1, sx2, sy2, src.width, src.height, dx1, dy1, dx2, dy2, dst.width, dst.height);

    [encoder setVertexBytes:quadTxVerticesBuffer length:sizeof(quadTxVerticesBuffer) atIndex:MeshVertexBuffer];
    [encoder setFragmentTexture:src atIndex: 0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
}

static
id<MTLTexture> replaceTextureRegion(MTLContext *mtlc, id<MTLTexture> dest, const SurfaceDataRasInfo * srcInfo, const MTLRasterFormatInfo * rfi, int dx1, int dy1, int dx2, int dy2) {
    const int dw = dx2 - dx1;
    const int dh = dy2 - dy1;

    const void * raster = srcInfo->rasBase;
    raster += srcInfo->bounds.y1*srcInfo->scanStride + srcInfo->bounds.x1*srcInfo->pixelStride;

    id<MTLTexture> result = nil;
    if (rfi->permuteMap != NULL) {
#if defined(__MAC_10_15) && __MAC_OS_X_VERSION_MAX_ALLOWED >= __MAC_10_15
        if (@available(macOS 10.15, *)) {
            @autoreleasepool {
                const uint8_t swzRed = revertPerm(rfi->permuteMap, 1);
                const uint8_t swzGreen = revertPerm(rfi->permuteMap, 2);
                const uint8_t swzBlue = revertPerm(rfi->permuteMap, 3);
                const uint8_t swzAlpha = revertPerm(rfi->permuteMap, 0);
                MTLTextureSwizzleChannels swizzle = MTLTextureSwizzleChannelsMake(
                        uint2swizzle(swzRed),
                        uint2swizzle(swzGreen),
                        uint2swizzle(swzBlue),
                        rfi->hasAlpha ? uint2swizzle(swzAlpha) : MTLTextureSwizzleOne
                );
                result = [dest
                        newTextureViewWithPixelFormat:MTLPixelFormatBGRA8Unorm
                        textureType:MTLTextureType2D
                        levels:NSMakeRange(0, 1) slices:NSMakeRange(0, 1)
                        swizzle:swizzle];
                J2dTraceLn5(J2D_TRACE_VERBOSE, "replaceTextureRegion [use swizzle for pooled]: %d, %d, %d, %d, hasA=%d",
                            swizzle.red, swizzle.green, swizzle.blue, swizzle.alpha, rfi->hasAlpha);
            }
        } else
#endif // __MAC_10_15 && __MAC_OS_X_VERSION_MAX_ALLOWED >= __MAC_10_15
        {
            // perform raster conversion
            // invoked only from rq-thread, so use static buffers
            // but it's better to use thread-local buffers (or special buffer manager)
            const int destRasterSize = dw*dh*4;

            static int bufferSize = 0;
            static void * buffer = NULL;
            if (buffer == NULL || bufferSize < destRasterSize) {
                bufferSize = destRasterSize;
                buffer = realloc(buffer, bufferSize);
            }
            if (buffer == NULL) {
                J2dTraceLn1(J2D_TRACE_ERROR, "replaceTextureRegion: can't alloc buffer for raster conversion, size=%d", bufferSize);
                bufferSize = 0;
                return nil;
            }
            vImage_Buffer srcBuf;
            srcBuf.height = dh;
            srcBuf.width = dw;
            srcBuf.rowBytes = srcInfo->scanStride;
            srcBuf.data = raster;

            vImage_Buffer destBuf;
            destBuf.height = dh;
            destBuf.width = dw;
            destBuf.rowBytes = dw*4;
            destBuf.data = buffer;

            vImagePermuteChannels_ARGB8888(&srcBuf, &destBuf, rfi->permuteMap, kvImageNoFlags);
            raster = buffer;

            J2dTraceLn5(J2D_TRACE_VERBOSE, "replaceTextureRegion [use conversion]: %d, %d, %d, %d, hasA=%d",
                        rfi->permuteMap[0], rfi->permuteMap[1], rfi->permuteMap[2], rfi->permuteMap[3], rfi->hasAlpha);
        }
    }

    MTLRegion region = MTLRegionMake2D(dx1, dy1, dw, dh);
    if (result != nil)
        dest = result;

    @autoreleasepool {
        id <MTLBlitCommandEncoder> blitEncoder = [mtlc.encoderManager createBlitEncoder];

        J2dTraceLn4(J2D_TRACE_VERBOSE, "replaceTextureRegion src (dw, dh) : [%d, %d] dest (dx1, dy1) =[%d, %d]",
                    dw, dh, dx1, dy1);

        id <MTLBuffer> buff = [[mtlc.device newBufferWithBytes:raster length:srcInfo->scanStride * dh options:MTLResourceStorageModeManaged] autorelease];
        [blitEncoder copyFromBuffer:buff
                sourceOffset:0 sourceBytesPerRow:srcInfo->scanStride sourceBytesPerImage:srcInfo->scanStride * dh sourceSize:MTLSizeMake(dw, dh, 1)
                toTexture:dest destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(dx1, dy1, 0)];
        [blitEncoder endEncoding];
    }

    return result;
}

/**
 * Inner loop used for copying a source system memory ("Sw") surface to a
 * destination MTL "Surface".  This method is invoked from
 * MTLBlitLoops_Blit().
 */

static void
MTLBlitSwToTextureViaPooledTexture(
        MTLContext *mtlc, SurfaceDataRasInfo *srcInfo, BMTLSDOps * bmtlsdOps,
        MTLRasterFormatInfo * rfi, jboolean useBlitEncoder, jint hint,
        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    const int sw = srcInfo->bounds.x2 - srcInfo->bounds.x1;
    const int sh = srcInfo->bounds.y2 - srcInfo->bounds.y1;
    id<MTLTexture> dest = bmtlsdOps->pTexture;

    MTLPooledTextureHandle * texHandle = [mtlc.texturePool getTexture:sw height:sh format:rfi->format];
    if (texHandle == nil) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLBlitSwToTextureViaPooledTexture: can't obtain temporary texture object from pool");
        return;
    }
    [[mtlc getCommandBufferWrapper] registerPooledTexture:texHandle];
    [texHandle release];

    id<MTLTexture> texBuff = texHandle.texture;
    id<MTLTexture> swizzledTexture = replaceTextureRegion(mtlc, texBuff, srcInfo, rfi, 0, 0, sw, sh);
    if (useBlitEncoder) {
        id <MTLBlitCommandEncoder> blitEncoder = [mtlc.encoderManager createBlitEncoder];
        [blitEncoder copyFromTexture:swizzledTexture != nil ? swizzledTexture : texBuff
                         sourceSlice:0
                         sourceLevel:0
                        sourceOrigin:MTLOriginMake(0, 0, 0)
                          sourceSize:MTLSizeMake(sw, sh, 1)
                           toTexture:dest
                    destinationSlice:0
                    destinationLevel:0
                   destinationOrigin:MTLOriginMake(dx1, dy1, 0)];
        [blitEncoder endEncoding];
    } else {
        drawTex2Tex(mtlc, swizzledTexture != nil ? swizzledTexture : texBuff, dest, !rfi->hasAlpha, bmtlsdOps->isOpaque, hint,
                    0, 0, sw, sh, dx1, dy1, dx2, dy2);
    }

    if (swizzledTexture != nil) {
        [swizzledTexture release];
    }
}

static
jboolean isIntegerAndUnscaled(
        jint sx1, jint sy1, jint sx2, jint sy2,
        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2
) {
    const jdouble epsilon = 0.0001f;

    // check that dx1,dy1 is integer
    if (fabs(dx1 - (int)dx1) > epsilon || fabs(dy1 - (int)dy1) > epsilon) {
        return JNI_FALSE;
    }
    // check that destSize equals srcSize
    if (fabs(dx2 - dx1 - sx2 + sx1) > epsilon || fabs(dy2 - dy1 - sy2 + sy1) > epsilon) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static
jboolean clipDestCoords(
        jdouble *dx1, jdouble *dy1, jdouble *dx2, jdouble *dy2,
        jint *sx1, jint *sy1, jint *sx2, jint *sy2,
        jint destW, jint destH, const MTLScissorRect * clipRect
) {
    // Trim destination rect by clip-rect (or dest.bounds)
    const jint sw    = *sx2 - *sx1;
    const jint sh    = *sy2 - *sy1;
    const jdouble dw = *dx2 - *dx1;
    const jdouble dh = *dy2 - *dy1;

    jdouble dcx1 = 0;
    jdouble dcx2 = destW;
    jdouble dcy1 = 0;
    jdouble dcy2 = destH;
    if (clipRect != NULL) {
        if (clipRect->x > dcx1)
            dcx1 = clipRect->x;
        const int maxX = clipRect->x + clipRect->width;
        if (dcx2 > maxX)
            dcx2 = maxX;
        if (clipRect->y > dcy1)
            dcy1 = clipRect->y;
        const int maxY = clipRect->y + clipRect->height;
        if (dcy2 > maxY)
            dcy2 = maxY;

        if (dcx1 >= dcx2) {
            J2dTraceLn2(J2D_TRACE_ERROR, "\tclipDestCoords: dcx1=%1.2f, dcx2=%1.2f", dcx1, dcx2);
            dcx1 = dcx2;
        }
        if (dcy1 >= dcy2) {
            J2dTraceLn2(J2D_TRACE_ERROR, "\tclipDestCoords: dcy1=%1.2f, dcy2=%1.2f", dcy1, dcy2);
            dcy1 = dcy2;
        }
    }
    if (*dx2 <= dcx1 || *dx1 >= dcx2 || *dy2 <= dcy1 || *dy1 >= dcy2) {
        J2dTraceLn(J2D_TRACE_INFO, "\tclipDestCoords: dest rect doesn't intersect clip area");
        J2dTraceLn4(J2D_TRACE_INFO, "\tdx2=%1.4f <= dcx1=%1.4f || *dx1=%1.4f >= dcx2=%1.4f", *dx2, dcx1, *dx1, dcx2);
        J2dTraceLn4(J2D_TRACE_INFO, "\t*dy2=%1.4f <= dcy1=%1.4f || *dy1=%1.4f >= dcy2=%1.4f", *dy2, dcy1, *dy1, dcy2);
        return JNI_FALSE;
    }
    if (*dx1 < dcx1) {
        J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdx1=%1.2f, will be clipped to %1.2f | sx1+=%d", *dx1, dcx1, (jint)((dcx1 - *dx1) * (sw/dw)));
        *sx1 += (jint)((dcx1 - *dx1) * (sw/dw));
        *dx1 = dcx1;
    }
    if (*dx2 > dcx2) {
        J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdx2=%1.2f, will be clipped to %1.2f | sx2-=%d", *dx2, dcx2, (jint)((*dx2 - dcx2) * (sw/dw)));
        *sx2 -= (jint)((*dx2 - dcx2) * (sw/dw));
        *dx2 = dcx2;
    }
    if (*dy1 < dcy1) {
        J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdy1=%1.2f, will be clipped to %1.2f | sy1+=%d", *dy1, dcy1, (jint)((dcy1 - *dy1) * (sh/dh)));
        *sy1 += (jint)((dcy1 - *dy1) * (sh/dh));
        *dy1 = dcy1;
    }
    if (*dy2 > dcy2) {
        J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdy2=%1.2f, will be clipped to %1.2f | sy2-=%d", *dy2, dcy2, (jint)((*dy2 - dcy2) * (sh/dh)));
        *sy2 -= (jint)((*dy2 - dcy2) * (sh/dh));
        *dy2 = dcy2;
    }
    return JNI_TRUE;
}

/**
 * General blit method for copying a native MTL surface to another MTL "Surface".
 * Parameter texture == true forces to use 'texture' codepath (dest coordinates will always be integers).
 * Parameter xform == true only when AffineTransform is used (invoked only from TransformBlit, dest coordinates will always be integers).
 */
void
MTLBlitLoops_IsoBlit(JNIEnv *env,
                     MTLContext *mtlc, jlong pSrcOps, jlong pDstOps,
                     jboolean xform, jint hint, jboolean texture,
                     jint sx1, jint sy1, jint sx2, jint sy2,
                     jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrcOps);
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(pDstOps);

    RETURN_IF_NULL(mtlc);
    RETURN_IF_NULL(srcOps);
    RETURN_IF_NULL(dstOps);

    id<MTLTexture> srcTex = srcOps->pTexture;
    id<MTLTexture> dstTex = dstOps->pTexture;
    if (srcTex == nil || srcTex == nil) {
        J2dTraceLn2(J2D_TRACE_ERROR, "MTLBlitLoops_IsoBlit: surface is null (stex=%p, dtex=%p)", srcTex, dstTex);
        return;
    }

    const jint sw    = sx2 - sx1;
    const jint sh    = sy2 - sy1;
    const jdouble dw = dx2 - dx1;
    const jdouble dh = dy2 - dy1;

    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
        J2dTraceLn4(J2D_TRACE_WARNING, "MTLBlitLoops_IsoBlit: invalid dimensions: sw=%d, sh%d, dw=%d, dh=%d", sw, sh, dw, dh);
        return;
    }

#ifdef DEBUG_ISOBLIT
    if ((xform == JNI_TRUE) != (mtlc.useTransform == JNI_TRUE)) {
        J2dTraceImpl(J2D_TRACE_ERROR, JNI_TRUE,
                "MTLBlitLoops_IsoBlit state error: xform=%d, mtlc.useTransform=%d, texture=%d",
                xform, mtlc.useTransform, texture);
    }
#endif // DEBUG_ISOBLIT

    clipDestCoords(
            &dx1, &dy1, &dx2, &dy2,
            &sx1, &sy1, &sx2, &sy2,
            dstTex.width, dstTex.height, texture ? NULL : [mtlc.clip getRect]
    );

    SurfaceDataBounds bounds;
    bounds.x1 = sx1;
    bounds.y1 = sy1;
    bounds.x2 = sx2;
    bounds.y2 = sy2;
    SurfaceData_IntersectBoundsXYXY(&bounds, 0, 0, srcOps->width, srcOps->height);

    if (bounds.x2 <= bounds.x1 || bounds.y2 <= bounds.y1) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLBlitLoops_IsoBlit: source rectangle doesn't intersect with source surface bounds");
        J2dTraceLn6(J2D_TRACE_VERBOSE, "  sx1=%d sy1=%d sx2=%d sy2=%d sw=%d sh=%d", sx1, sy1, sx2, sy2, srcOps->width, srcOps->height);
        J2dTraceLn4(J2D_TRACE_VERBOSE, "  dx1=%f dy1=%f dx2=%f dy2=%f", dx1, dy1, dx2, dy2);
        return;
    }

    if (bounds.x1 != sx1) {
        dx1 += (bounds.x1 - sx1) * (dw / sw);
        sx1 = bounds.x1;
    }
    if (bounds.y1 != sy1) {
        dy1 += (bounds.y1 - sy1) * (dh / sh);
        sy1 = bounds.y1;
    }
    if (bounds.x2 != sx2) {
        dx2 += (bounds.x2 - sx2) * (dw / sw);
        sx2 = bounds.x2;
    }
    if (bounds.y2 != sy2) {
        dy2 += (bounds.y2 - sy2) * (dh / sh);
        sy2 = bounds.y2;
    }

#ifdef TRACE_ISOBLIT
    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_FALSE,
         "MTLBlitLoops_IsoBlit [tx=%d, xf=%d, AC=%s]: src=%s, dst=%s | (%d, %d, %d, %d)->(%1.2f, %1.2f, %1.2f, %1.2f)",
         texture, xform, [mtlc getCompositeDescription].cString,
         getSurfaceDescription(srcOps).cString, getSurfaceDescription(dstOps).cString,
         sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
#endif //TRACE_ISOBLIT

    if (!texture && !xform
        && [mtlc isBlendingDisabled:srcOps->isOpaque]
        && isIntegerAndUnscaled(sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2)
        && (dstOps->isOpaque || !srcOps->isOpaque)
    ) {
#ifdef TRACE_ISOBLIT
        J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE," [via blitEncoder]");
#endif //TRACE_ISOBLIT

        id <MTLBlitCommandEncoder> blitEncoder = [mtlc.encoderManager createBlitEncoder];
        [blitEncoder copyFromTexture:srcTex
                         sourceSlice:0
                         sourceLevel:0
                        sourceOrigin:MTLOriginMake(sx1, sy1, 0)
                          sourceSize:MTLSizeMake(sx2 - sx1, sy2 - sy1, 1)
                           toTexture:dstTex
                    destinationSlice:0
                    destinationLevel:0
                   destinationOrigin:MTLOriginMake(dx1, dy1, 0)];
        [blitEncoder endEncoding];
        return;
    }

#ifdef TRACE_ISOBLIT
    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE," [via sampling]");
#endif //TRACE_ISOBLIT
    drawTex2Tex(mtlc, srcTex, dstTex,
            [mtlc isBlendingDisabled:srcOps->isOpaque],
            dstOps->isOpaque, hint, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
}

/**
 * General blit method for copying a system memory ("Sw") surface to a native MTL surface.
 * Parameter texture == true only in SwToTextureBlit (straight copy from sw to texture), dest coordinates will always be integers.
 * Parameter xform == true only when AffineTransform is used (invoked only from TransformBlit, dest coordinates will always be integers).
 */
void
MTLBlitLoops_Blit(JNIEnv *env,
                  MTLContext *mtlc, jlong pSrcOps, jlong pDstOps,
                  jboolean xform, jint hint,
                  jint srctype, jboolean texture,
                  jint sx1, jint sy1, jint sx2, jint sy2,
                  jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    SurfaceDataOps *srcOps = (SurfaceDataOps *)jlong_to_ptr(pSrcOps);
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(pDstOps);

    RETURN_IF_NULL(mtlc);
    RETURN_IF_NULL(srcOps);
    RETURN_IF_NULL(dstOps);

    id<MTLTexture> dest = dstOps->pTexture;
    if (dest == NULL) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLBlitLoops_Blit: dest is null");
        return;
    }
    if (srctype < 0 || srctype >= sizeof(RasterFormatInfos)/ sizeof(MTLRasterFormatInfo)) {
        J2dTraceLn1(J2D_TRACE_ERROR, "MTLBlitLoops_Blit: source pixel format %d isn't supported", srctype);
        return;
    }
    const jint sw    = sx2 - sx1;
    const jint sh    = sy2 - sy1;
    const jdouble dw = dx2 - dx1;
    const jdouble dh = dy2 - dy1;

    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLBlitLoops_Blit: invalid dimensions");
        return;
    }

#ifdef DEBUG_BLIT
    if (
        (xform == JNI_TRUE) != (mtlc.useTransform == JNI_TRUE)
        || (xform && texture)
    ) {
        J2dTraceImpl(J2D_TRACE_ERROR, JNI_TRUE,
                "MTLBlitLoops_Blit state error: xform=%d, mtlc.useTransform=%d, texture=%d",
                xform, mtlc.useTransform, texture);
    }
    if (texture) {
        if (!isIntegerAndUnscaled(sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2)) {
            J2dTraceImpl(J2D_TRACE_ERROR, JNI_TRUE,
                    "MTLBlitLoops_Blit state error: texture=true, but src and dst dimensions aren't equal or dest coords aren't integers");
        }
        if (!dstOps->isOpaque && !RasterFormatInfos[srctype].hasAlpha) {
            J2dTraceImpl(J2D_TRACE_ERROR, JNI_TRUE,
                    "MTLBlitLoops_Blit state error: texture=true, but dest has alpha and source hasn't alpha, can't use texture-codepath");
        }
    }
#endif // DEBUG_BLIT

    clipDestCoords(
            &dx1, &dy1, &dx2, &dy2,
            &sx1, &sy1, &sx2, &sy2,
            dest.width, dest.height, texture ? NULL : [mtlc.clip getRect]
    );

    SurfaceDataRasInfo srcInfo;
    srcInfo.bounds.x1 = sx1;
    srcInfo.bounds.y1 = sy1;
    srcInfo.bounds.x2 = sx2;
    srcInfo.bounds.y2 = sy2;

    // NOTE: This function will modify the contents of the bounds field to represent the maximum available raster data.
    if (srcOps->Lock(env, srcOps, &srcInfo, SD_LOCK_READ) != SD_SUCCESS) {
        J2dTraceLn(J2D_TRACE_WARNING, "MTLBlitLoops_Blit: could not acquire lock");
        return;
    }

    if (srcInfo.bounds.x2 > srcInfo.bounds.x1 && srcInfo.bounds.y2 > srcInfo.bounds.y1) {
        srcOps->GetRasInfo(env, srcOps, &srcInfo);
        if (srcInfo.rasBase) {
            if (srcInfo.bounds.x1 != sx1) {
                const int dx = srcInfo.bounds.x1 - sx1;
                dx1 += dx * (dw / sw);
            }
            if (srcInfo.bounds.y1 != sy1) {
                const int dy = srcInfo.bounds.y1 - sy1;
                dy1 += dy * (dh / sh);
            }
            if (srcInfo.bounds.x2 != sx2) {
                const int dx = srcInfo.bounds.x2 - sx2;
                dx2 += dx * (dw / sw);
            }
            if (srcInfo.bounds.y2 != sy2) {
                const int dy = srcInfo.bounds.y2 - sy2;
                dy2 += dy * (dh / sh);
            }

#ifdef TRACE_BLIT
            J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_FALSE,
                    "MTLBlitLoops_Blit [tx=%d, xf=%d, AC=%s]: bdst=%s, src=%p (%dx%d) O=%d premul=%d | (%d, %d, %d, %d)->(%1.2f, %1.2f, %1.2f, %1.2f)",
                    texture, xform, [mtlc getCompositeDescription].cString,
                    getSurfaceDescription(dstOps).cString, srcOps,
                    sx2 - sx1, sy2 - sy1,
                    RasterFormatInfos[srctype].hasAlpha ? 0 : 1, RasterFormatInfos[srctype].isPremult ? 1 : 0,
                    sx1, sy1, sx2, sy2,
                    dx1, dy1, dx2, dy2);
#endif //TRACE_BLIT

            MTLRasterFormatInfo rfi = RasterFormatInfos[srctype];
            const jboolean useReplaceRegion = texture ||
                    ([mtlc isBlendingDisabled:!rfi.hasAlpha]
                    && !xform
                    && isIntegerAndUnscaled(sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2));

            if (useReplaceRegion) {
                if (dstOps->isOpaque || rfi.hasAlpha) {
#ifdef TRACE_BLIT
                    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE," [replaceTextureRegion]");
#endif //TRACE_BLIT
                    replaceTextureRegion(mtlc, dest, &srcInfo, &rfi, (int) dx1, (int) dy1, (int) dx2, (int) dy2);
                } else {
#ifdef TRACE_BLIT
                    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE," [via pooled + blit]");
#endif //TRACE_BLIT
                    MTLBlitSwToTextureViaPooledTexture(mtlc, &srcInfo, dstOps, &rfi, false, hint, dx1, dy1, dx2, dy2);
                }
            } else { // !useReplaceRegion
#ifdef TRACE_BLIT
                J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE," [via pooled texture]");
#endif //TRACE_BLIT
                MTLBlitSwToTextureViaPooledTexture(mtlc, &srcInfo, dstOps, &rfi, false, hint, dx1, dy1, dx2, dy2);
            }
        }
        SurfaceData_InvokeRelease(env, srcOps, &srcInfo);
    }
    SurfaceData_InvokeUnlock(env, srcOps, &srcInfo);
}

/**
 * Specialized blit method for copying a native MTL "Surface" (pbuffer,
 * window, etc.) to a system memory ("Sw") surface.
 */
void
MTLBlitLoops_SurfaceToSwBlit(JNIEnv *env, MTLContext *mtlc,
                             jlong pSrcOps, jlong pDstOps, jint dsttype,
                             jint srcx, jint srcy, jint dstx, jint dsty,
                             jint width, jint height)
{
    J2dTraceLn6(J2D_TRACE_VERBOSE, "MTLBlitLoops_SurfaceToSwBlit: sx=%d sy=%d w=%d h=%d dx=%d dy=%d", srcx, srcy, width, height, dstx, dsty);

    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrcOps);
    SurfaceDataOps *dstOps = (SurfaceDataOps *)jlong_to_ptr(pDstOps);
    SurfaceDataRasInfo srcInfo, dstInfo;

    if (dsttype < 0 || dsttype >= sizeof(RasterFormatInfos)/ sizeof(MTLRasterFormatInfo)) {
        J2dTraceLn1(J2D_TRACE_ERROR, "MTLBlitLoops_SurfaceToSwBlit: destination pixel format %d isn't supported", dsttype);
        return;
    }

    if (width <= 0 || height <= 0) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLBlitLoops_SurfaceToSwBlit: dimensions are non-positive");
        return;
    }

    RETURN_IF_NULL(srcOps);
    RETURN_IF_NULL(dstOps);
    RETURN_IF_NULL(mtlc);

    srcInfo.bounds.x1 = srcx;
    srcInfo.bounds.y1 = srcy;
    srcInfo.bounds.x2 = srcx + width;
    srcInfo.bounds.y2 = srcy + height;
    dstInfo.bounds.x1 = dstx;
    dstInfo.bounds.y1 = dsty;
    dstInfo.bounds.x2 = dstx + width;
    dstInfo.bounds.y2 = dsty + height;

    if (dstOps->Lock(env, dstOps, &dstInfo, SD_LOCK_WRITE) != SD_SUCCESS) {
        J2dTraceLn(J2D_TRACE_WARNING,"MTLBlitLoops_SurfaceToSwBlit: could not acquire dst lock");
        return;
    }

    SurfaceData_IntersectBoundsXYXY(&srcInfo.bounds,
                                    0, 0, srcOps->width, srcOps->height);

    SurfaceData_IntersectBlitBounds(&dstInfo.bounds, &srcInfo.bounds,
                                    srcx - dstx, srcy - dsty);

    if (srcInfo.bounds.x2 > srcInfo.bounds.x1 &&
        srcInfo.bounds.y2 > srcInfo.bounds.y1)
    {
        dstOps->GetRasInfo(env, dstOps, &dstInfo);
        if (dstInfo.rasBase) {
            void *pDst = dstInfo.rasBase;

            srcx = srcInfo.bounds.x1;
            srcy = srcInfo.bounds.y1;
            dstx = dstInfo.bounds.x1;
            dsty = dstInfo.bounds.y1;
            width = srcInfo.bounds.x2 - srcInfo.bounds.x1;
            height = srcInfo.bounds.y2 - srcInfo.bounds.y1;

            pDst = PtrAddBytes(pDst, dstx * dstInfo.pixelStride);
            pDst = PtrPixelsRow(pDst, dsty, dstInfo.scanStride);

            // Metal texture is (0,0) at left-top
            srcx = srcOps->xOffset + srcx;
            srcy = srcOps->yOffset + srcy;
            const int srcLength = width * height * 4; // NOTE: assume that src format is MTLPixelFormatBGRA8Unorm

            // Create MTLBuffer (or use static)
            MTLRasterFormatInfo rfi = RasterFormatInfos[dsttype];
            const jboolean directCopy = rfi.permuteMap == NULL;

            id<MTLBuffer> mtlbuf;
#ifdef USE_STATIC_BUFFER
            if (directCopy) {
                // NOTE: theoretically we can use newBufferWithBytesNoCopy, but pDst must be allocated with special API
                // mtlbuf = [mtlc.device
                //          newBufferWithBytesNoCopy:pDst
                //                            length:(NSUInteger) srcLength
                //                           options:MTLResourceCPUCacheModeDefaultCache
                //                       deallocator:nil];
                //
                // see https://developer.apple.com/documentation/metal/mtldevice/1433382-newbufferwithbytesnocopy?language=objc
                //
                // The storage allocation of the returned new MTLBuffer object is the same as the pointer input value.
                // The existing memory allocation must be covered by a single VM region, typically allocated with vm_allocate or mmap.
                // Memory allocated by malloc is specifically disallowed.
            }

            static id<MTLBuffer> mtlIntermediateBuffer = nil; // need to reimplement with MTLBufferManager
            if (mtlIntermediateBuffer == nil || mtlIntermediateBuffer.length < srcLength) {
                if (mtlIntermediateBuffer != nil) {
                    [mtlIntermediateBuffer release];
                }
                mtlIntermediateBuffer = [mtlc.device newBufferWithLength:srcLength options:MTLResourceCPUCacheModeDefaultCache];
            }
            mtlbuf = mtlIntermediateBuffer;
#else // USE_STATIC_BUFFER
            mtlbuf = [mtlc.device newBufferWithLength:width*height*4 options:MTLResourceStorageModeShared];
#endif // USE_STATIC_BUFFER

            // Read from surface into MTLBuffer
            // NOTE: using of separate blitCommandBuffer can produce errors (draw into surface (with general cmd-buf)
            // can be unfinished when reading raster from blit cmd-buf).
            // Consider to use [mtlc.encoderManager createBlitEncoder] and [mtlc commitCommandBuffer:JNI_TRUE];
            J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLBlitLoops_SurfaceToSwBlit: source texture %p", srcOps->pTexture);

            id<MTLCommandBuffer> cb = [mtlc createCommandBuffer];
            id<MTLBlitCommandEncoder> blitEncoder = [cb blitCommandEncoder];
            [blitEncoder synchronizeTexture:srcOps->pTexture slice:0 level:0];
            [blitEncoder copyFromTexture:srcOps->pTexture
                            sourceSlice:0
                            sourceLevel:0
                           sourceOrigin:MTLOriginMake(srcx, srcy, 0)
                             sourceSize:MTLSizeMake(width, height, 1)
                               toBuffer:mtlbuf
                      destinationOffset:0 /*offset already taken in: pDst = PtrAddBytes(pDst, dstx * dstInfo.pixelStride)*/
                 destinationBytesPerRow:width*4
               destinationBytesPerImage:width * height*4];
            [blitEncoder endEncoding];

            // Commit and wait for reading complete
            [cb commit];
            [cb waitUntilCompleted];

            // Perform conversion if necessary
            if (directCopy) {
                if ((dstInfo.scanStride == width * dstInfo.pixelStride) &&
                    (height == (dstInfo.bounds.y2 - dstInfo.bounds.y1))) {
                    // mtlbuf.contents have same dimensions as of pDst
                    memcpy(pDst, mtlbuf.contents, srcLength);
                } else {
                    // mtlbuf.contents have smaller dimensions than pDst
                    // copy each row from mtlbuf.contents at appropriate position in pDst
                    // Note : pDst is already addjusted for offsets using PtrAddBytes above

                    int rowSize = width * dstInfo.pixelStride;
                    for (int y = 0; y < height; y++) {
                        memcpy(pDst, mtlbuf.contents + (y * rowSize), rowSize);
                        pDst = PtrAddBytes(pDst, dstInfo.scanStride);
                    }
                }
            } else {
                J2dTraceLn6(J2D_TRACE_VERBOSE,"MTLBlitLoops_SurfaceToSwBlit: dsttype=%d, raster conversion will be performed, dest rfi: %d, %d, %d, %d, hasA=%d",
                            dsttype, rfi.permuteMap[0], rfi.permuteMap[1], rfi.permuteMap[2], rfi.permuteMap[3], rfi.hasAlpha);

                // perform raster conversion: mtlIntermediateBuffer(8888) -> pDst(rfi)
                // invoked only from rq-thread, so use static buffers
                // but it's better to use thread-local buffers (or special buffer manager)
                vImage_Buffer srcBuf;
                srcBuf.height = height;
                srcBuf.width = width;
                srcBuf.rowBytes = 4*width;
                srcBuf.data = mtlbuf.contents;

                vImage_Buffer destBuf;
                destBuf.height = height;
                destBuf.width = width;
                destBuf.rowBytes = dstInfo.scanStride;
                destBuf.data = pDst;

                vImagePermuteChannels_ARGB8888(&srcBuf, &destBuf, rfi.permuteMap, kvImageNoFlags);
            }
#ifndef USE_STATIC_BUFFER
            [mtlbuf release];
#endif // USE_STATIC_BUFFER
        }
        SurfaceData_InvokeRelease(env, dstOps, &dstInfo);
    }
    SurfaceData_InvokeUnlock(env, dstOps, &dstInfo);
}

void
MTLBlitLoops_CopyArea(JNIEnv *env,
                      MTLContext *mtlc, BMTLSDOps *dstOps,
                      jint x, jint y, jint width, jint height,
                      jint dx, jint dy)
{
#ifdef DEBUG
    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE, "MTLBlitLoops_CopyArea: bdst=%p [tex=%p] %dx%d | src (%d, %d), %dx%d -> dst (%d, %d)",
            dstOps, dstOps->pTexture, ((id<MTLTexture>)dstOps->pTexture).width, ((id<MTLTexture>)dstOps->pTexture).height, x, y, width, height, dx, dy);
#endif //DEBUG

    @autoreleasepool {
    id<MTLCommandBuffer> cb = [mtlc createCommandBuffer];
    id<MTLBlitCommandEncoder> blitEncoder = [cb blitCommandEncoder];

    // Create an intrermediate buffer
    int totalBuffsize = width * height * 4;
    id <MTLBuffer> buff = [[mtlc.device newBufferWithLength:totalBuffsize options:MTLResourceStorageModePrivate] autorelease];

    [blitEncoder copyFromTexture:dstOps->pTexture
            sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(x, y, 0) sourceSize:MTLSizeMake(width, height, 1)
             toBuffer:buff destinationOffset:0 destinationBytesPerRow:(width * 4) destinationBytesPerImage:totalBuffsize];

    [blitEncoder copyFromBuffer:buff
            sourceOffset:0 sourceBytesPerRow:width*4 sourceBytesPerImage:totalBuffsize sourceSize:MTLSizeMake(width, height, 1)
            toTexture:dstOps->pTexture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(x + dx, y + dy, 0)];
    [blitEncoder endEncoding];

    [cb commit];
    //[cb waitUntilCompleted];

    /*[blitEncoder
            copyFromTexture:dstOps->pTexture
            sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(x, y, 0) sourceSize:MTLSizeMake(width, height, 1)
            toTexture:dstOps->pTexture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(x + dx, y + dy, 0)];
    [blitEncoder endEncoding];*/

    }
    // TODO:
    //  1. check rect bounds
    //  2. support CopyArea with extra-alpha (and with custom Composite if necessary)
}

#endif /* !HEADLESS */
