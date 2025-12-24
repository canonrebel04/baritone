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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.*;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 * @since 8/9/2018
 */
public final class PathRenderer implements IRenderer {

    private PathRenderer() {}

    public static double posX() {
        return renderManager.renderPosX();
    }

    public static double posY() {
        return renderManager.renderPosY();
    }

    public static double posZ() {
        return renderManager.renderPosZ();
    }

    public static void render(RenderEvent event, PathingBehavior behavior) {
        final IPlayerContext ctx = behavior.ctx;
        if (ctx.world() == null) {
            return;
        }
        if (ctx.minecraft().screen instanceof GuiClick) {
            ((GuiClick) ctx.minecraft().screen).onRender(event.getModelViewStack(), event.getProjectionMatrix());
        }

        final float partialTicks = event.getPartialTicks();
        final Goal goal = behavior.getGoal();

        final DimensionType thisPlayerDimension = ctx.world().dimensionType();
        final DimensionType currentRenderViewDimension = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world().dimensionType();

        if (thisPlayerDimension != currentRenderViewDimension) {
            // this is a path for a bot in a different dimension, don't render it
            return;
        }

        // Standard Rendering (Matrix hacks removed)
        {
            if (goal != null && settings.renderGoal.value) {
                drawGoal(event.getModelViewStack(), ctx, goal, partialTicks, settings.colorGoalBox.value);
            }
    
            if (!settings.renderPath.value) {
                return;
            }
    
            PathExecutor current = behavior.getCurrent(); // this should prevent most race conditions?
            PathExecutor next = behavior.getNext(); // like, now it's not possible for current!=null to be true, then suddenly false because of another thread
            if (current != null && settings.renderSelectionBoxes.value) {
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toBreak(), settings.colorBlocksToBreak.value);
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toPlace(), settings.colorBlocksToPlace.value);
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toWalkInto(), settings.colorBlocksToWalkInto.value);
            }
    
            //drawManySelectionBoxes(player, Collections.singletonList(behavior.pathStart()), partialTicks, Color.WHITE);
    
            // Render the current path, if there is one
            if (current != null && current.getPath() != null) {
                int renderBegin = Math.max(current.getPosition() - 3, 0);
                drawPath(event.getModelViewStack(), current.getPath().positions(), renderBegin, settings.colorCurrentPath.value, settings.fadePath.value, 10, 20);
            }
    
            if (next != null && next.getPath() != null) {
                drawPath(event.getModelViewStack(), next.getPath().positions(), 0, settings.colorNextPath.value, settings.fadePath.value, 10, 20);
            }
    
            // If there is a path calculation currently running, render the path calculation process
            behavior.getInProgress().ifPresent(currentlyRunning -> {
                currentlyRunning.bestPathSoFar().ifPresent(p -> {
                    drawPath(event.getModelViewStack(), p.positions(), 0, settings.colorBestPathSoFar.value, settings.fadePath.value, 10, 20);
                });
    
                currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {
                    drawPath(event.getModelViewStack(), mr.positions(), 0, settings.colorMostRecentConsidered.value, settings.fadePath.value, 10, 20);
                    drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), Collections.singletonList(mr.getDest()), settings.colorMostRecentConsidered.value);
                });
            });
        }
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0) {
        drawPath(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, 0.5D);
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
        // Modern Path Rendering: Convert to Vec3, Spline Interpolate, and Render Ribbon
        
        if (positions.isEmpty()) return;

        java.util.List<net.minecraft.world.phys.Vec3> splinePoints = new java.util.ArrayList<>();
        
        stack.pushPose();
        // Use World Coordinates directly. PoseStack is Identity.
        // NOTE: High precision loss possible at >100k blocks. 
        // But ensures no "Flying Lines" from double-subtraction.

        for (int i = startIndex; i < positions.size(); i++) {
             BetterBlockPos pos = positions.get(i);
             splinePoints.add(new net.minecraft.world.phys.Vec3(
                 pos.x + offset,
                 pos.y + offset,
                 pos.z + offset
             ));
        }

        // 2. Interpolate
        int quality = 4; // Points per segment
        java.util.List<net.minecraft.world.phys.Vec3> smoothPath = CatmullRomSpline.interpolate(splinePoints, quality);

        // 3. Render Ribbon
        // We use IRenderer.startTriangles to get a buffer in Triangle mode.
        BufferBuilder bufferBuilder = IRenderer.startTriangles(color, 0.5f);
        
        RibbonRenderer.renderRibbon(bufferBuilder, stack, smoothPath, 0.5f, color);
        
        IRenderer.endTriangles(bufferBuilder, settings.renderPathIgnoreDepth.value);
        
        stack.popPose();
    }

    private static void emitPathLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, double offset) {
        final double extraOffset = offset + 0.03D;

        double vpX = posX();
        double vpY = posY();
        double vpZ = posZ();
        boolean renderPathAsFrickinThingy = !settings.renderPathAsLine.value;
        
        double startX = x1 + offset - vpX;
        double startY = y1 + offset - vpY;
        double startZ = z1 + offset - vpZ;
        
        double endX = x2 + offset - vpX;
        double endY = y2 + offset - vpY;
        double endZ = z2 + offset - vpZ;
        
        // Use PoseStack translation to set the origin to start position
        // This keeps vertex coordinates small/zero (preserving precision)
        stack.pushPose();
        stack.translate((float)startX, (float)startY, (float)startZ);
        
        // Relative end position
        float rX2 = (float)(endX - startX);
        float rY2 = (float)(endY - startY);
        float rZ2 = (float)(endZ - startZ);
        
        IRenderer.emitLine(bufferBuilder, stack, 
            0, 0, 0, 
            rX2, rY2, rZ2
        );
        
        if (renderPathAsFrickinThingy) {
           float extra = (float)(extraOffset - offset);
           // Lines for the "FrickinThingy" (Vertical bits)
           // Relative to start position (which is at origin now)
           
           // Vertical line at End
           IRenderer.emitLine(bufferBuilder, stack,
               rX2, rY2, rZ2,
               rX2, rY2 + extra, rZ2
           );
           
           // Horizontal top line back to Start
           IRenderer.emitLine(bufferBuilder, stack,
               rX2, rY2 + extra, rZ2,
               0, extra, 0
           );
           
           // Vertical line at Start
           IRenderer.emitLine(bufferBuilder, stack,
               0, extra, 0,
               0, 0, 0
           );
        }
        
        stack.popPose();
    }

    public static void drawManySelectionBoxes(PoseStack stack, Entity player, Collection<BlockPos> positions, Color color) {
        BufferBuilder bufferBuilder = IRenderer.startLines(color, settings.pathRenderLineWidthPixels.value);

        //BlockPos blockpos = movingObjectPositionIn.getBlockPos();
        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext()); // TODO this assumes same dimension between primary baritone and render view? is this safe?

        positions.forEach(pos -> {
            BlockState state = bsi.get0(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
            toDraw = toDraw.move(pos);
            IRenderer.emitAABB(bufferBuilder, stack, toDraw, .002D);
        });

        IRenderer.endLines(bufferBuilder, settings.renderSelectionBoxesIgnoreDepth.value);
    }

    public static void drawGoal(PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color) {
        drawGoal(null, stack, ctx, goal, partialTicks, color, true);
    }

    private static void drawGoal(@Nullable BufferBuilder bufferBuilder, PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color, boolean setupRender) {
        if (!setupRender && bufferBuilder == null) {
            throw new RuntimeException("BufferBuilder must not be null if setupRender is false");
        }
        double renderPosX = posX();
        double renderPosY = posY();
        double renderPosZ = posZ();
        double minX, maxX;
        double minZ, maxZ;
        double minY, maxY;
        double y, y1, y2;
        if (!settings.renderGoalAnimated.value) {
            // y = 1 causes rendering issues when the player is at the same y as the top of a block for some reason
            y = 0.999F;
        } else {
            y = Mth.cos((float) (((float) ((System.nanoTime() / 100000L) % 20000L)) / 20000F * Math.PI * 2));
        }
        if (goal instanceof IGoalRenderPos) {
            BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();
            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y /= 2;
            }
            y1 = 1 + y + goalPos.getY() - renderPosY;
            y2 = 1 - y + goalPos.getY() - renderPosY;
            minY = goalPos.getY() - renderPosY;
            maxY = minY + 2;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y1 -= 0.5;
                y2 -= 0.5;
                maxY--;
            }
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalXZ) {
            GoalXZ goalPos = (GoalXZ) goal;
            minY = ctx.world().getMinY();
            maxY = ctx.world().getMaxY();

            if (settings.renderGoalXZBeacon.value) {
                // todo: fix beacon renderer (has been broken since at least 1.20.4)
                //  issue with outer beam rendering, probably related to matrix transforms state not matching vanilla
                //  possible solutions:
                //      inject hook into LevelRenderer#renderBlockEntities where the matrices have already been set up correctly
                //      copy out and modify the vanilla beacon render code
                //  also another issue on 1.21.5 is we don't have a simple method call for editing the beacon's depth test
//                return;
            }

            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;

            y1 = 0;
            y2 = 0;
            minY -= renderPosY;
            maxY -= renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalComposite) {
            // Simple way to determine if goals can be batched, without having some sort of GoalRenderer
            boolean batch = Arrays.stream(((GoalComposite) goal).goals()).allMatch(IGoalRenderPos.class::isInstance);
            BufferBuilder buf = bufferBuilder;
            if (batch) {
                buf = IRenderer.startLines(color, settings.goalRenderLineWidthPixels.value);
            }
            for (Goal g : ((GoalComposite) goal).goals()) {
                drawGoal(buf, stack, ctx, g, partialTicks, color, !batch);
            }
            if (batch) {
                IRenderer.endLines(buf, settings.renderGoalIgnoreDepth.value);
            }
        } else if (goal instanceof GoalInverted) {
            drawGoal(stack, ctx, ((GoalInverted) goal).origin, partialTicks, settings.colorInvertedGoalBox.value);
        } else if (goal instanceof GoalYLevel) {
            GoalYLevel goalpos = (GoalYLevel) goal;
            minX = ctx.player().position().x - settings.yLevelBoxSize.value - renderPosX;
            minZ = ctx.player().position().z - settings.yLevelBoxSize.value - renderPosZ;
            maxX = ctx.player().position().x + settings.yLevelBoxSize.value - renderPosX;
            maxZ = ctx.player().position().z + settings.yLevelBoxSize.value - renderPosZ;
            minY = ((GoalYLevel) goal).level - renderPosY;
            maxY = minY + 2;
            y1 = 1 + y + goalpos.level - renderPosY;
            y2 = 1 - y + goalpos.level - renderPosY;
            drawDankLitGoalBox(bufferBuilder, stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        }
    }

    private static void drawDankLitGoalBox(BufferBuilder bufferBuilder, PoseStack stack, Color colorIn, double minX, double maxX, double minZ, double maxZ, double minY, double maxY, double y1, double y2, boolean setupRender) {
        if (setupRender) {
            bufferBuilder = IRenderer.startLines(colorIn, settings.goalRenderLineWidthPixels.value);
        }

        renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y1);
        renderHorizontalQuad(bufferBuilder, stack, minX, maxX, minZ, maxZ, y2);

        for (double y = minY; y < maxY; y += 16) {
            double max = Math.min(maxY, y + 16);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, minX, max, minZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, max, minZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, maxX, max, maxZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, max, maxZ, 0.0, 1.0, 0.0);
        }

        if (setupRender) {
            IRenderer.endLines(bufferBuilder, settings.renderGoalIgnoreDepth.value);
        }
    }

    private static void renderHorizontalQuad(BufferBuilder bufferBuilder, PoseStack stack, double minX, double maxX, double minZ, double maxZ, double y) {
        if (y != 0) {
            IRenderer.emitLine(bufferBuilder, stack, minX, y, minZ, maxX, y, minZ, 1.0, 0.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, minZ, maxX, y, maxZ, 0.0, 0.0, 1.0);
            IRenderer.emitLine(bufferBuilder, stack, maxX, y, maxZ, minX, y, maxZ, -1.0, 0.0, 0.0);
            IRenderer.emitLine(bufferBuilder, stack, minX, y, maxZ, minX, y, minZ, 0.0, 0.0, -1.0);
        }
    }
}
