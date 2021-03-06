/*
 *  Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */
package jdk.internal.foreign;

import jdk.internal.access.foreign.MemoryAddressProxy;
import jdk.internal.misc.Unsafe;

import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;

import java.lang.ref.Reference;
import java.util.Objects;

/**
 * This class provides an immutable implementation for the {@code MemoryAddress} interface. This class contains information
 * about the segment this address is associated with, as well as an offset into such segment.
 */
public final class MemoryAddressImpl implements MemoryAddress, MemoryAddressProxy {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    private final MemorySegmentImpl segment;
    private final long offset;

    public MemoryAddressImpl(MemorySegmentImpl segment) {
        this(segment, 0);
    }

    public MemoryAddressImpl(MemorySegmentImpl segment, long offset) {
        this.segment = Objects.requireNonNull(segment);
        this.offset = offset;
    }

    public static void copy(MemoryAddressImpl src, MemoryAddressImpl dst, long size) {
        src.checkAccess(0, size, true);
        dst.checkAccess(0, size, false);
        //check disjoint
        long offsetSrc = src.unsafeGetOffset();
        long offsetDst = dst.unsafeGetOffset();
        Object baseSrc = src.unsafeGetBase();
        Object baseDst = dst.unsafeGetBase();
        UNSAFE.copyMemory(baseSrc, offsetSrc, baseDst, offsetDst, size);
    }

    // MemoryAddress methods

    @Override
    public long offset() {
        return offset;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public MemoryAddress addOffset(long bytes) {
        return new MemoryAddressImpl(segment, offset + bytes);
    }

    @Override
    public MemoryAddress rebase(MemorySegment segment) {
        MemorySegmentImpl segmentImpl = (MemorySegmentImpl)segment;
        if (segmentImpl.base != this.segment.base) {
            throw new IllegalArgumentException("Invalid rebase target: " + segment);
        }
        return new MemoryAddressImpl((MemorySegmentImpl)segment,
                unsafeGetOffset() - ((MemoryAddressImpl)segment.baseAddress()).unsafeGetOffset());
    }

    // MemoryAddressProxy methods

    public void checkAccess(long offset, long length, boolean readOnly) {
        segment.checkRange(MemoryAddressProxy.addOffsets(this.offset, offset, this), length, !readOnly);
    }

    public long unsafeGetOffset() {
        return segment.min + offset;
    }

    public Object unsafeGetBase() {
        return segment.base();
    }

    @Override
    public boolean isSmall() {
        return segment.isSmall();
    }

    // Object methods

    @Override
    public int hashCode() {
        return Objects.hash(unsafeGetBase(), unsafeGetOffset());
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof MemoryAddressImpl) {
            MemoryAddressImpl addr = (MemoryAddressImpl)that;
            return Objects.equals(unsafeGetBase(), ((MemoryAddressImpl) that).unsafeGetBase()) &&
                    unsafeGetOffset() == addr.unsafeGetOffset();
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "MemoryAddress{ region: " + segment + " offset=0x" + Long.toHexString(offset) + " }";
    }

    // helper methods

    public static long addressof(MemoryAddress address) {
        MemoryAddressImpl addressImpl = (MemoryAddressImpl)address;
        if (addressImpl.unsafeGetBase() != null) {
            throw new IllegalStateException("Heap address!");
        }
        return addressImpl.unsafeGetOffset();
    }

    public static MemoryAddress ofLongUnchecked(long value) {
        return ofLongUnchecked(value, Long.MAX_VALUE);
    }

    public static MemoryAddress ofLongUnchecked(long value, long byteSize) {
        return new MemoryAddressImpl((MemorySegmentImpl)Utils.makeNativeSegmentUnchecked(value, byteSize), 0);
    }
}
