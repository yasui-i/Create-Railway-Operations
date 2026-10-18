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
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SlidingDoorMovementBehaviour.class)
public class SlidingDoorMovementBehaviourMixin {
   // 主力：时刻表配置了车门控制条件（DoorControlCondition）且正在运行时，完全接管车门。
   // 取消 tickOpen，让 Create 原生与 custom_train_door 的车门开关、站台门联动都不再执行，
   // 车门开关完全由 DoorControlCondition 决定。tick 里 tickOpen 之后的动画更新不受影响。
   @Inject(
      method = {"tickOpen"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void railway_operations$takeoverTickOpen(MovementContext context, boolean isOpen, CallbackInfo ci) {
      if (scheduleControlsDoor(context)) {
         ci.cancel();
      }
   }

   // 兜底：若 custom_train_door 的 tickOpen 先于本 mixin 执行，它会自行调用 shouldOpen；
   // 这里把 shouldOpen 压成 false，让它也拿不到“该开门”的信号，从而不干预 DoorControlCondition。
   @Inject(
      method = {"shouldOpen"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void railway_operations$suppressShouldOpen(MovementContext context, CallbackInfoReturnable<Boolean> cir) {
      if ((Boolean) cir.getReturnValue() && scheduleControlsDoor(context)) {
         cir.setReturnValue(false);
      }
   }

   private static boolean scheduleControlsDoor(MovementContext context) {
      Contraption contraption = context.contraption;
      if (contraption == null || !(contraption.entity instanceof CarriageContraptionEntity cce)) {
         return false;
      }
      Carriage carriage = cce.getCarriage();
      if (carriage == null || carriage.train == null) {
         return false;
      }
      return scheduleHasDoorControl(carriage.train);
   }

   private static boolean scheduleHasDoorControl(Train train) {
      ScheduleRuntime runtime = train.runtime;
      if (runtime == null || runtime.schedule == null) {
         return false;
      }

      // 手操（paused 且时刻表未跑完）时不接管，让 Create 原生正常管理车门；
      // 但时刻表已跑完（completed）后仍保持 DoorControlCondition 的关门状态，
      // 避免 Create 原生的“到站开门”在条件结束后又把门打开。
      if (runtime.paused && !runtime.completed) {
         return false;
      }

      for (ScheduleEntry entry : runtime.schedule.entries) {
         if (entry.instruction != null && entry.instruction.supportsConditions()) {
            for (List<ScheduleWaitCondition> group : entry.conditions) {
               for (ScheduleWaitCondition cond : group) {
                  if (cond instanceof DoorControlCondition) {
                     return true;
                  }
               }
            }
         }
      }

      return false;
   }
}
