/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.foreign.abi.x64.windows;

import jdk.incubator.foreign.CSupport;
import jdk.incubator.foreign.ForeignLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.internal.foreign.abi.UpcallStubs;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

import static jdk.incubator.foreign.CSupport.*;

/**
 * ABI implementation based on Windows ABI AMD64 supplement v.0.99.6
 */
public class Windowsx64Linker implements ForeignLinker {

    public static final int MAX_INTEGER_ARGUMENT_REGISTERS = 4;
    public static final int MAX_INTEGER_RETURN_REGISTERS = 1;
    public static final int MAX_VECTOR_ARGUMENT_REGISTERS = 4;
    public static final int MAX_VECTOR_RETURN_REGISTERS = 1;
    public static final int MAX_REGISTER_ARGUMENTS = 4;
    public static final int MAX_REGISTER_RETURNS = 1;

    private static Windowsx64Linker instance;

    static final long ADDRESS_SIZE = 64; // bits

    private static final MethodHandle MH_unboxVaList;
    private static final MethodHandle MH_boxVaList;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MH_unboxVaList = lookup.findStatic(Windowsx64Linker.class, "unboxVaList",
                MethodType.methodType(MemoryAddress.class, CSupport.VaList.class));
            MH_boxVaList = lookup.findStatic(Windowsx64Linker.class, "boxVaList",
                MethodType.methodType(VaList.class, MemoryAddress.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static Windowsx64Linker getInstance() {
        if (instance == null) {
            instance = new Windowsx64Linker();
        }
        return instance;
    }

    public static VaList newVaList(Consumer<VaList.Builder> actions) {
        WinVaList.Builder builder = WinVaList.builder();
        actions.accept(builder);
        return builder.build();
    }

    private static MethodType convertVaListCarriers(MethodType mt) {
        Class<?>[] params = new Class<?>[mt.parameterCount()];
        for (int i = 0; i < params.length; i++) {
            Class<?> pType = mt.parameterType(i);
            params[i] = ((pType == CSupport.VaList.class) ? WinVaList.CARRIER : pType);
        }
        return MethodType.methodType(mt.returnType(), params);
    }

    private static MethodHandle unxboxVaLists(MethodType type, MethodHandle handle) {
        for (int i = 0; i < type.parameterCount(); i++) {
            if (type.parameterType(i) == VaList.class) {
               handle = MethodHandles.filterArguments(handle, i, MH_unboxVaList);
            }
        }
        return handle;
    }

    @Override
    public MethodHandle downcallHandle(MemoryAddress symbol, MethodType type, FunctionDescriptor function) {
        MethodType llMt = convertVaListCarriers(type);
        MethodHandle handle = CallArranger.arrangeDowncall(symbol, llMt, function);
        handle = unxboxVaLists(type, handle);
        return handle;
    }

    private static MethodHandle boxVaLists(MethodHandle handle) {
        MethodType type = handle.type();
        for (int i = 0; i < type.parameterCount(); i++) {
            if (type.parameterType(i) == VaList.class) {
               handle = MethodHandles.filterArguments(handle, i, MH_boxVaList);
            }
        }
        return handle;
    }

    @Override
    public MemorySegment upcallStub(MethodHandle target, FunctionDescriptor function) {
        target = boxVaLists(target);
        return UpcallStubs.upcallAddress(CallArranger.arrangeUpcall(target, target.type(), function));
    }

    @Override
    public String name() {
        return Win64.NAME;
    }

    static Win64.ArgumentClass argumentClassFor(MemoryLayout layout) {
        return (Win64.ArgumentClass)layout.attribute(Win64.CLASS_ATTRIBUTE_NAME).get();
    }

    private static MemoryAddress unboxVaList(CSupport.VaList list) {
        return ((WinVaList) list).getSegment().baseAddress();
    }

    private static CSupport.VaList boxVaList(MemoryAddress ma) {
        return WinVaList.ofAddress(ma);
    }

    public static VaList newVaListOfAddress(MemoryAddress ma) {
        return WinVaList.ofAddress(ma);
    }

}
