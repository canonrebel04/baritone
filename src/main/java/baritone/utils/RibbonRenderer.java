package baritone.utils;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.awt.Color;
import java.util.List;

public class RibbonRenderer {

    public static void renderRibbon(VertexConsumer buffer, PoseStack stack, List<Vec3> points, float width, Color color) {
        if (points.size() < 2) return;

        float halfWidth = width / 2.0f;
        
        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        int alpha = color.getAlpha();

        Matrix4f pose = stack.last().pose();

        // Calculate time-based flow
        long time = System.currentTimeMillis();
        float flowOffset = -(time % 2000) / 2000.0f; // 1 cycle per 2 seconds

        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 pCurrent = points.get(i);
            Vec3 pNext = points.get(i + 1);

            // Calculate 'Right' vector
            Vec3 dir = pNext.subtract(pCurrent).normalize();
            Vec3 up = new Vec3(0, 1, 0); 
            if (Math.abs(dir.y) > 0.95) {
                up = new Vec3(1, 0, 0);
            }

            Vec3 right = dir.cross(up).normalize().scale(halfWidth);

            float v1 = i / (float)points.size() + flowOffset;
            float v2 = (i + 1) / (float)points.size() + flowOffset;

            // Vertices
            Vec3 vRightCur = pCurrent.add(right);
            Vec3 vLeftCur = pCurrent.subtract(right);
            Vec3 vLeftNext = pNext.subtract(right);
            Vec3 vRightNext = pNext.add(right);

            // Triangle 1 (RightCur, LeftCur, LeftNext)
            addVertex(buffer, pose, vRightCur, red, green, blue, alpha, 0, v1);
            addVertex(buffer, pose, vLeftCur, red, green, blue, alpha, 1, v1);
            addVertex(buffer, pose, vLeftNext, red, green, blue, alpha, 1, v2);

            // Triangle 2 (RightCur, LeftNext, RightNext)
            addVertex(buffer, pose, vRightCur, red, green, blue, alpha, 0, v1);
            addVertex(buffer, pose, vLeftNext, red, green, blue, alpha, 1, v2);
            addVertex(buffer, pose, vRightNext, red, green, blue, alpha, 0, v2);
        }
    }

    private static void addVertex(VertexConsumer buffer, Matrix4f pose, Vec3 pos, int r, int g, int b, int a, float u, float v) {
        buffer.addVertex(pose, (float)pos.x, (float)pos.y, (float)pos.z)
              .setColor(r, g, b, a)
              //.setUv(u, v) // Requires a RenderLayer that supports UVs. Debug/Lines usually doesn't.
              // We will need a specific RenderType for this.
              // For now, let's assume we might just use PositionColor.
              // Use Normal for "fake" UVs if needed? Or just ignore UVs for the first pass (Solid Color Ribbon).
              .setNormal(0, 1, 0); 
    }
}
