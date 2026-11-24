package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.client.simulation.niagara.NativeVectorMath;

/** The source chooses its matching algorithm using Windows float log and SSE rounding. */
final class NativeParameterStoreMath {
    static { NativeVectorMath.initialize(); }
    private NativeParameterStoreMath() { }
    static native boolean useMerge(int sourceCount, int destinationCount);
}
