package com.railway.railway_operations.mixin;

import com.railway.railway_operations.block.GhostSeatFullBlock;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds +0.5 Y when the contraption seat block is a GhostSeatFullBlock,
 * so passengers sit at full-block height even when assembled.
 */
@Mixin(AbstractContraptionEntity.class)
public class ContraptionSeatHeightMixin {

    @Shadow(remap = false)
    Contraption contraption;

    @Inject(method = "getPassengerPosition", at = @At("RETURN"), cancellable = true, remap = false)
    private void railway_operations$adjustFullBlockSeatHeight(Entity passenger, float partialTicks,
                                                                CallbackInfoReturnable<Vec3> cir) {
        Vec3 pos = cir.getReturnValue();
        if (pos == null || contraption == null) return;

        BlockPos seatPos = contraption.getSeatOf(passenger.getUUID());
        if (seatPos == null) return;

        StructureBlockInfo info = contraption.getBlocks().get(seatPos);
        if (info != null && info.state().getBlock() instanceof GhostSeatFullBlock) {
            cir.setReturnValue(new Vec3(pos.x, pos.y + 0.5, pos.z));
        }
    }
}
