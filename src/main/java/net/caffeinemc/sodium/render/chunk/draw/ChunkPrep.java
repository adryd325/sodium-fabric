package net.caffeinemc.sodium.render.chunk.draw;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceArrayMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.caffeinemc.gfx.api.buffer.*;
import net.caffeinemc.gfx.api.device.RenderDevice;
import net.caffeinemc.sodium.render.buffer.VertexRange;
import net.caffeinemc.sodium.render.chunk.RenderSection;
import net.caffeinemc.sodium.render.chunk.passes.ChunkRenderPass;
import net.caffeinemc.sodium.render.chunk.passes.DefaultRenderPasses;
import net.caffeinemc.sodium.render.chunk.region.RenderRegionManager;
import net.caffeinemc.sodium.render.chunk.state.ChunkRenderBounds;
import net.caffeinemc.sodium.render.terrain.quad.properties.ChunkMeshFace;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.system.MemoryUtil;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.*;

public class ChunkPrep {
    private static final int COMMAND_STRUCT_STRIDE = 20;
    private static final int INSTANCE_STRUCT_STRIDE = 16;

    public static Map<ChunkRenderPass, PreparedRenderList> createRenderLists(RenderDevice device, RenderRegionManager regionManager,
                                                                             ReferenceArrayList<RenderSection> unsortedChunks, ChunkCameraContext camera) {
        if (unsortedChunks.isEmpty()) {
            return null;
        }

        var sortedChunks = new ChunkRenderList(regionManager, unsortedChunks);

        var builders = new Reference2ReferenceArrayMap<ChunkRenderPass, RenderListBuilder>();
        var alignment = device.properties().uniformBufferOffsetAlignment();

        var commandBufferCapacity = commandBufferSize(alignment, sortedChunks);
        var instanceBufferCapacity = instanceBufferSize(alignment, sortedChunks);

        for (var pass : DefaultRenderPasses.ALL) {
            builders.put(pass, new RenderListBuilder(device, commandBufferCapacity, instanceBufferCapacity, alignment));
        }

        var largestVertexIndex = 0;
        var reverseOrder = false; // TODO: fix me

        for (var bucketIterator = sortedChunks.sorted(reverseOrder); bucketIterator.hasNext(); ) {
            var bucket = bucketIterator.next();

            for (var sectionIterator = bucket.sorted(reverseOrder); sectionIterator.hasNext(); ) {
                var section = sectionIterator.next();
                createCommands(section, builders, camera);
            }

            for (var builder : builders.values()) {
                var instanceCount = builder.instanceBufferBuilder.pending();
                var commandCount = builder.commandBufferBuilder.pending();

                if (commandCount <= 0) {
                    continue;
                }

                var region = bucket.region();

                var instanceData = builder.instanceBufferBuilder.flush();
                var commandData = builder.commandBufferBuilder.flush();

                builder.batches.add(new ChunkRenderBatch(
                        region.vertexBuffers.getBufferObject(),
                        region.vertexBuffers.getStride(),
                        instanceCount,
                        commandCount,
                        instanceData,
                        commandData
                ));

                largestVertexIndex = Math.max(largestVertexIndex, builder.maxVertexIndex);
            }
        }

        var lists = new Reference2ReferenceArrayMap<ChunkRenderPass, PreparedRenderList>();

        for (var entry : builders.entrySet()) {
            var pass = entry.getKey();
            var builder = entry.getValue();

            var commandBuffer = device.createBuffer(builder.commandBuffer, 0, builder.commandBufferBuilder.length());
            var instanceBuffer = device.createBuffer(builder.instanceBuffer, 0, builder.instanceBufferBuilder.length());

            lists.put(pass, new PreparedRenderList(commandBuffer, instanceBuffer, builder.batches, largestVertexIndex));
        }

        return lists;
    }

    private static void createCommands(RenderSection section, Reference2ReferenceArrayMap<ChunkRenderPass, RenderListBuilder> builders, ChunkCameraContext camera) {
        var geometry = section.getGeometry();
        var baseVertex = geometry.segment.getOffset();

        var visibility = calculateVisibilityFlags(section.getBounds(), camera);

        for (var model : geometry.models) {
            if ((model.visibilityBits & visibility) == 0) {
                continue;
            }

            var pass = model.pass;
            var builder = builders.get(pass);

            if (builder == null) {
                continue;
            }

            var commandBufferBuilder = builder.commandBufferBuilder;
            var instanceBufferBuilder = builder.instanceBufferBuilder;

            for (int face = 0; face < ChunkMeshFace.COUNT; face++) {
                if ((visibility & (1 << face)) == 0) {
                    continue;
                }

                var range = model.ranges[face];

                if (range == VertexRange.NULL) {
                    continue;
                }

                var firstVertex = baseVertex + VertexRange.unpackFirstVertex(range);
                var vertexCount = VertexRange.unpackVertexCount(range);

                commandBufferBuilder.push(firstVertex, vertexCount, instanceBufferBuilder.pending());
            }

            float x = getCameraTranslation(ChunkSectionPos.getBlockCoord(section.getChunkX()), camera.blockX, camera.deltaX);
            float y = getCameraTranslation(ChunkSectionPos.getBlockCoord(section.getChunkY()), camera.blockY, camera.deltaY);
            float z = getCameraTranslation(ChunkSectionPos.getBlockCoord(section.getChunkZ()), camera.blockZ, camera.deltaZ);

            instanceBufferBuilder.push(x, y, z);

            builder.maxVertexIndex = Math.max(builder.maxVertexIndex, geometry.segment.getLength());
        }
    }

    public static void deleteRenderLists(RenderDevice device, Map<ChunkRenderPass, PreparedRenderList> renderLists) {
        for (var entry : renderLists.values()) {
            device.deleteBuffer(entry.commandBuffer);
            device.deleteBuffer(entry.instanceBuffer);
        }
    }

    public record ChunkRenderBatch(Buffer vertexBuffer,
                                   int vertexStride,
                                   int instanceCount,
                                   int commandCount,
                                   BufferSlice instanceData,
                                   BufferSlice commandData
    ) {
    }

    public record PreparedRenderList(Buffer commandBuffer,
                                     Buffer instanceBuffer,
                                     List<ChunkRenderBatch> batches,
                                     int largestVertexIndex) {

    }

    private static class RenderListBuilder {
        public final AllocatedBuffer commandBuffer;
        public final AllocatedBuffer instanceBuffer;

        public final CommandBufferBuilder commandBufferBuilder;
        public final InstanceBufferBuilder instanceBufferBuilder;

        private final List<ChunkRenderBatch> batches = new ArrayList<>();

        public int maxVertexIndex;

        private RenderListBuilder(RenderDevice device, long commandBufferCapacity, long instanceBufferCapacity, int alignment) {
            // TODO: we're mapping as persistent, but this is unnecessary
            this.commandBuffer = device.allocateBuffer(commandBufferCapacity, false);
            this.instanceBuffer = device.allocateBuffer(instanceBufferCapacity, false);

            this.commandBufferBuilder = new CommandBufferBuilder(this.commandBuffer.view(), alignment);
            this.instanceBufferBuilder = new InstanceBufferBuilder(this.instanceBuffer.view(), alignment);
        }
    }

    private static abstract class BufferBuilder {
        protected final ByteBuffer buffer;
        protected final long addr;

        protected final int alignment;
        protected final int stride;

        protected final int capacity;

        protected int count;
        protected int mark;
        protected int position;

        private BufferBuilder(ByteBuffer buffer, int alignment, int stride) {
            this.buffer = buffer;
            this.alignment = alignment;
            this.stride = stride;

            this.position = buffer.position();
            this.capacity = buffer.capacity();

            this.addr = MemoryUtil.memAddress(this.buffer);
        }

        public BufferSlice flush() {
            var slice = new BufferSlice(this.mark, this.count * this.stride);

            this.count = 0;
            this.position = MathHelper.roundUpToMultiple(this.position, this.alignment);
            this.mark = this.position;

            return slice;
        }

        protected long next() {
            if (this.position + this.stride > this.capacity) {
                throw new BufferOverflowException();
            }

            var ptr = this.addr + this.position;
            this.position += this.stride;
            this.count++;

            return ptr;
        }

        public int pending() {
            return this.count;
        }

        public int length() {
            return this.position;
        }
    }

    private static class CommandBufferBuilder extends BufferBuilder {
        private CommandBufferBuilder(ByteBuffer buffer, int alignment) {
            super(buffer, alignment, COMMAND_STRUCT_STRIDE);
        }

        public void push(int vertexStart, int vertexCount, int baseInstance) {
            long ptr = this.next();
            MemoryUtil.memPutInt(ptr + 0, vertexCount);
            MemoryUtil.memPutInt(ptr + 4, 1);
            MemoryUtil.memPutInt(ptr + 8, 0);
            MemoryUtil.memPutInt(ptr + 12, vertexStart);
            MemoryUtil.memPutInt(ptr + 16, baseInstance);
        }
    }

    private static class InstanceBufferBuilder extends BufferBuilder {
        private InstanceBufferBuilder(ByteBuffer buffer, int alignment) {
            super(buffer, alignment, INSTANCE_STRUCT_STRIDE);
        }

        public void push(float x, float y, float z) {
            long ptr = this.next();
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);
        }
    }

    public record BufferSlice(int offset, int length) {

    }

    private static long commandBufferSize(int alignment, ChunkRenderList list) {
        int size = 0;

        for (var bucket : list.unsorted()) {
            size += MathHelper.roundUpToMultiple((bucket.size() * ChunkMeshFace.COUNT) * COMMAND_STRUCT_STRIDE, alignment);
        }

        return size;
    }

    private static long instanceBufferSize(int alignment, ChunkRenderList list) {
        int size = 0;

        for (var bucket : list.unsorted()) {
            size += MathHelper.roundUpToMultiple(bucket.size() * INSTANCE_STRUCT_STRIDE, alignment);
        }

        return size;
    }

    private static float getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraPos) {
        return (chunkBlockPos - cameraBlockPos) - cameraPos;
    }

    private static int calculateVisibilityFlags(ChunkRenderBounds bounds, ChunkCameraContext camera) {
        int flags = ChunkMeshFace.UNASSIGNED_BITS;

        if (camera.posY > bounds.y1) {
            flags |= ChunkMeshFace.UP_BITS;
        }

        if (camera.posY < bounds.y2) {
            flags |= ChunkMeshFace.DOWN_BITS;
        }

        if (camera.posX > bounds.x1) {
            flags |= ChunkMeshFace.EAST_BITS;
        }

        if (camera.posX < bounds.x2) {
            flags |= ChunkMeshFace.WEST_BITS;
        }

        if (camera.posZ > bounds.z1) {
            flags |= ChunkMeshFace.SOUTH_BITS;
        }

        if (camera.posZ < bounds.z2) {
            flags |= ChunkMeshFace.NORTH_BITS;
        }

        return flags;
    }

}
