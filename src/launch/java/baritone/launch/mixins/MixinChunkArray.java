/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.utils.accessor.IChunkArray;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(targets = "net.minecraft.class_631$class_3681")
public abstract class MixinChunkArray implements IChunkArray {
    @Final
    @Shadow
    AtomicReferenceArray<LevelChunk> field_16251; // chunks
    @Final
    @Shadow
    int field_16253; // radius

    @Shadow
    int field_19204; // centerChunkX
    @Shadow
    int field_19205; // centerChunkZ
    @Shadow
    int field_19143; // loadedChunkCount

    @Shadow
    abstract boolean method_16034(int x, int z); // isInRadius

    @Shadow
    abstract int method_16027(int x, int z); // getIndex

    @Shadow
    protected abstract void method_16031(int index, LevelChunk chunk); // set

    @Override
    public int centerX() {
        return field_19204;
    }

    @Override
    public int centerZ() {
        return field_19205;
    }

    @Override
    public int viewDistance() {
        return field_16253;
    }

    @Override
    public AtomicReferenceArray<LevelChunk> getChunks() {
        return field_16251;
    }

    @Override
    public void copyFrom(IChunkArray other) {
        field_19204 = other.centerX();
        field_19205 = other.centerZ();

        AtomicReferenceArray<LevelChunk> copyingFrom = other.getChunks();
        for (int k = 0; k < copyingFrom.length(); ++k) {
            LevelChunk chunk = copyingFrom.get(k);
            if (chunk != null) {
                ChunkPos chunkpos = chunk.getPos();
                if (method_16034(chunkpos.x, chunkpos.z)) {
                    int index = method_16027(chunkpos.x, chunkpos.z);
                    if (field_16251.get(index) != null) {
                        throw new IllegalStateException("Doing this would mutate the client's REAL loaded chunks?!");
                    }
                    method_16031(index, chunk);
                }
            }
        }
    }
}
