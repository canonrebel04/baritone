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

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.server.ReloadableServerRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LootContext.Builder.class)
public abstract class MixinLootContextBuilder {

    @Shadow public abstract ServerLevel getLevel();

    @Redirect(method = "method_309", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;method_58576()Lnet/minecraft/class_9383$class_9385;"))
    public net.minecraft.server.ReloadableServerRegistries.Holder reloadableRegistries(MinecraftServer instance) {
        if (getLevel() instanceof BlockOptionalMeta.ServerLevelStub) {
            BlockOptionalMeta.ServerLevelStub sls = (BlockOptionalMeta.ServerLevelStub) getLevel();
            return sls.lookup();
        }
        // return instance.reloadableRegistries();
        try {
            java.lang.reflect.Method m;
            try {
                m = net.minecraft.server.MinecraftServer.class.getMethod("method_58576");
            } catch (NoSuchMethodException e) {
                m = net.minecraft.server.MinecraftServer.class.getMethod("reloadableRegistries");
            }
            return (net.minecraft.server.ReloadableServerRegistries.Holder) m.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke reloadableRegistries", e);
        }
    }

}
