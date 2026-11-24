package com.kltyton.lumenfx.client.simulation.niagara;

import java.util.concurrent.ThreadLocalRandom;

final class NiagaraSeed {
    private NiagaraSeed() { }

    static int next() { return ThreadLocalRandom.current().nextInt() & 0x7fff; }

    /** UE adds the component offset to the source seed in a 32-bit word. */
    static int offset(int source, int componentOffset) { return source + componentOffset; }
}
