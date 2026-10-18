package com.railway.railway_operations.block;

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Same as GhostSeatBlock (no collision, train-assemblable) but places the
 * SeatEntity higher so the passenger sits at full-block height (Y + 1.0).
 */
public class GhostSeatFullBlock extends SeatBlock {

    public GhostSeatFullBlock() {
        super(Properties.of()
                .strength(0.5F)
                .noOcclusion()
                .sound(SoundType.WOOL),
                DyeColor.WHITE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return Shapes.block();
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

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        ItemInteractionResult result = super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (result.consumesAction() && !level.isClientSide) {
            for (SeatEntity seat : level.getEntitiesOfClass(
                    SeatEntity.class, new AABB(pos).expandTowards(0, 1, 0))) {
                seat.setPos(seat.getX(), pos.getY() + 0.5, seat.getZ());
                break;
            }
        }
        return result;
    }
}
