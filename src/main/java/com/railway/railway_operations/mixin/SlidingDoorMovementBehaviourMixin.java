package com.railway.railway_operations.mixin;

import com.railway.railway_operations.condition.DoorControlCondition;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorMovementBehaviour;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * When the schedule has any "Follow Station" door control condition,
 * suppress the station-based auto-open entirely. The schedule condition
 * takes full responsibility for door timing.
 * <p>
 * Suppression is active as long as the train is at a station (shouldOpen
 * only returns true when the train is stopped) and the schedule is on
 * the train. Once the train departs, shouldOpen returns false naturally.
 */
@Mixin(SlidingDoorMovementBehaviour.class)
public class SlidingDoorMovementBehaviourMixin {

    @Inject(method = "shouldOpen", at = @At("RETURN"), cancellable = true)
    private void railway_operations$suppressAutoOpen(MovementContext context,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        Contraption contraption = context.contraption;
        if (contraption == null || !(contraption.entity instanceof CarriageContraptionEntity cce)) return;

        Carriage carriage = cce.getCarriage();
        if (carriage == null || carriage.train == null) return;

        if (scheduleHasFollowStation(carriage.train)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean scheduleHasFollowStation(Train train) {
        ScheduleRuntime runtime = train.runtime;
        if (runtime == null || runtime.schedule == null) return false;

        for (ScheduleEntry entry : runtime.schedule.entries) {
            if (entry.instruction == null || !entry.instruction.supportsConditions()) continue;
            for (List<ScheduleWaitCondition> group : entry.conditions) {
                for (ScheduleWaitCondition cond : group) {
                    if (cond instanceof DoorControlCondition dcc && dcc.isFollowStation()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
