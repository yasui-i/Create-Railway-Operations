package com.railway.railway_operations.block;

import com.simibubi.create.AllShapes;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An invisible, non-colliding seat that extends Create's SeatBlock.
 * Same block properties as a normal SeatBlock so train assembly works,
 * but with empty collision and an empty model for invisibility.
 */
public class GhostSeatBlock extends SeatBlock {

    public GhostSeatBlock() {
        super(Properties.of()
                .strength(0.5F)
                .noOcclusion()
                .sound(SoundType.WOOL),
                DyeColor.WHITE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return AllShapes.SEAT;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        super.fallOn(level, state, pos, entity, fallDistance * 0.5F);
    }
}
