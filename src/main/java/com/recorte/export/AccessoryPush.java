package com.recorte.export;

import java.util.List;

/**
 * Pure geometry: where to move a captured accessory so it sits just OUTSIDE the player body instead of
 * clipping into it. Separated from {@link LayerCapturer} (which pulls in Minecraft classes) so the logic
 * can be unit-tested headlessly.
 *
 * <p>The push moves the WHOLE accessory horizontally, along whichever axis (X or Z) it is most offset on,
 * just past the body's <em>measured</em> surface <em>at the accessory's own height</em> — a geometric
 * guarantee rather than a fixed fudge factor. It is a no-op when the accessory is already clear or isn't
 * beside the body at that height.
 */
public final class AccessoryPush {
    private AccessoryPush() {}

    /** Gap left between a pushed accessory and the body surface. */
    public static final float MARGIN = 0.03f;

    /** Returns {@code [pushX, pushZ]} (export-space units). Inputs are {x,y,z} vertex positions. */
    public static float[] compute(List<float[]> accPositions, List<float[]> bodyVerts) {
        if (bodyVerts == null || bodyVerts.isEmpty() || accPositions == null || accPositions.isEmpty()) {
            return new float[]{0f, 0f};
        }
        // accessory horizontal footprint
        float aMinX = 1e9f, aMaxX = -1e9f, aMinZ = 1e9f, aMaxZ = -1e9f;
        for (float[] p : accPositions) {
            aMinX = Math.min(aMinX, p[0]); aMaxX = Math.max(aMaxX, p[0]);
            aMinZ = Math.min(aMinZ, p[2]); aMaxZ = Math.max(aMaxZ, p[2]);
        }
        // body horizontal footprint. (The body is built from CUBES, whose vertices only exist at the
        // cube corners — so filtering by the accessory's exact height finds nothing for a chest item.
        // Use the whole footprint and push the accessory clear of it.)
        float bMinX = 1e9f, bMaxX = -1e9f, bMinZ = 1e9f, bMaxZ = -1e9f;
        for (float[] v : bodyVerts) {
            bMinX = Math.min(bMinX, v[0]); bMaxX = Math.max(bMaxX, v[0]);
            bMinZ = Math.min(bMinZ, v[2]); bMaxZ = Math.max(bMaxZ, v[2]);
        }

        // already clear of the body footprint? leave it alone
        if (aMinX > bMaxX || aMaxX < bMinX || aMinZ > bMaxZ || aMaxZ < bMinZ) {
            return new float[]{0f, 0f};
        }
        // overlapping — move it the SHORTEST way out, just past the nearest face. Prefer Z (forward/back,
        // off the chest) over X (sideways, past the arms) when the two are equal.
        float ePX = (bMaxX + MARGIN) - aMinX;   // distance to push +X
        float eNX = aMaxX - (bMinX - MARGIN);   // distance to push -X
        float ePZ = (bMaxZ + MARGIN) - aMinZ;
        float eNZ = aMaxZ - (bMinZ - MARGIN);
        float best = Math.min(Math.min(ePZ, eNZ), Math.min(ePX, eNX));
        if (best == ePZ) return new float[]{0f, ePZ};
        if (best == eNZ) return new float[]{0f, -eNZ};
        if (best == ePX) return new float[]{ePX, 0f};
        return new float[]{-eNX, 0f};
    }
}
