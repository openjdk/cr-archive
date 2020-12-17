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

#ifndef COMMON_H
#define COMMON_H

#include <simd/SIMD.h>

#define PGRAM_VERTEX_COUNT 6
#define QUAD_VERTEX_COUNT 4

enum VertexAttributes {
    VertexAttributePosition = 0,
    VertexAttributeTexPos = 1
};

enum BufferIndex  {
    MeshVertexBuffer = 0,
    FrameUniformBuffer = 1,
    MatrixBuffer = 2
};

struct FrameUniforms {
    vector_float4 color;
};

struct TransformMatrix {
    matrix_float4x4 transformMatrix;
};

struct GradFrameUniforms {
    vector_float3 params;
    vector_float4 color1;
    vector_float4 color2;
    int isCyclic;
};

struct Vertex {
    float position[2];
};

struct TxtVertex {
    float position[2];
    float txtpos[2];
};

// These values are mapped from AffineTransformOp
#define INTERPOLATION_NEAREST_NEIGHBOR 1
#define INTERPOLATION_BILINEAR 2
// #define INTERPOLATION_BICUBIC 3
// NOTE: Metal samplers doesn't supports bicubic interpolation
// see table 2.7 from https://developer.apple.com/metal/Metal-Shading-Language-Specification.pdf
// (probably we need to implement separate fragment shader with bicubic interpolation)

struct TxtFrameUniforms {
    vector_float4 color;
    int mode; // NOTE: consider to use bit fields
    int isSrcOpaque;
    int isDstOpaque;
    float extraAlpha;
};

struct TxtFrameOpRescaleUniforms {
    vector_float4 color;
    float extraAlpha;

    int isSrcOpaque;
    int isNonPremult;

    vector_float4 normScaleFactors;
    vector_float4 normOffsets;
};

struct TxtFrameOpConvolveUniforms {
    float extraAlpha;
    vector_float4 imgEdge;
    int kernelSize;
    int isEdgeZeroFill;
};

struct TxtFrameOpLookupUniforms {
    float extraAlpha;
    vector_float4 offset;
    int isUseSrcAlpha;
    int isNonPremult;
};

struct AnchorData
{
    vector_float3 xParams;
    vector_float3 yParams;
};

struct LCDFrameUniforms {
    vector_float3 src_adj;
    vector_float3 gamma;
    vector_float3 invgamma;
};
#endif
