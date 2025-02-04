package net.impleri.itemskills;

import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.InteractionEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.value.IntValue;
import net.impleri.itemskills.restrictions.Restrictions;
import net.impleri.playerskills.commands.PlayerSkillsCommands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

class ItemEvents {
    private int updateDelay = 0;

    public void registerEventHandlers() {
        LifecycleEvent.SERVER_STARTING.register(this::onStartup);
        TickEvent.PLAYER_POST.register(this::onPlayerTick);

        PlayerEvent.PICKUP_ITEM_PRE.register(this::beforePlayerPickup);

        InteractionEvent.LEFT_CLICK_BLOCK.register(this::beforeUseItemBlock);
        InteractionEvent.RIGHT_CLICK_BLOCK.register(this::beforeUseItemBlock);
        InteractionEvent.RIGHT_CLICK_ITEM.register(this::beforeUseItem);
        InteractionEvent.INTERACT_ENTITY.register(this::beforeInteractEntity);

        EntityEvent.LIVING_HURT.register(this::beforePlayerAttack);
        BlockEvent.BREAK.register(this::beforeMine);
    }

    public void registerCommands() {
        CommandRegistrationEvent.EVENT.register(this::registerDebugCommand);
    }

    private void registerDebugCommand(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registry, Commands.CommandSelection selection) {
        PlayerSkillsCommands.registerDebug(dispatcher, "itemskills", PlayerSkillsCommands.toggleDebug("Item Skills", ItemSkills::toggleDebug));
    }

    private void onStartup(MinecraftServer minecraftServer) {
        if (Platform.isModLoaded("kubejs")) {
            net.impleri.itemskills.integrations.kubejs.ItemSkillsPlugin.onStartup(minecraftServer);
        }
    }

    private void onPlayerTick(Player player) {
        if (player.getLevel().isClientSide) {
            return;
        }

        if(updateDelay > 0) {
            updateDelay--;
            return;
        }

        updateDelay = 10;

        Inventory inventory = player.getInventory();

        // Move unwearable items from armor and offhand into normal inventory
        InventoryHelper.filterFromList(player, inventory.armor);
        InventoryHelper.filterFromList(player, inventory.offhand);

        // Get unholdable items from inventory
        List<ItemStack> itemsToRemove = InventoryHelper.getItemsToRemove(player, inventory.items);

        // Drop the unholdable items from the normal inventory
        if (!itemsToRemove.isEmpty()) {
            ItemSkills.LOGGER.debug("{} is holding {} item(s) that should be dropped", player.getName().getString(), itemsToRemove.size());
            itemsToRemove.forEach(InventoryHelper.dropFromInventory(player));
        }
    }

    private EventResult beforePlayerPickup(Player player, ItemEntity entity, ItemStack stack) {
        var item = ItemHelper.getItem(stack);

        if (ItemHelper.isHoldable(player, item, null)) {
            return EventResult.pass();
        }

        ItemSkills.LOGGER.debug("{} is about to pickup {}", player.getName().getString(), ItemHelper.getItemKey(item));

        return EventResult.interruptFalse();
    }

    private EventResult beforePlayerAttack(LivingEntity entity, DamageSource source, float amount) {
        var attacker = source.getEntity();

        if (attacker instanceof Player player) {
            var weapon = ItemHelper.getItem(player.getMainHandItem());

            if (!Restrictions.INSTANCE.isHarmful(player, weapon, null)) {
                ItemSkills.LOGGER.debug("{} was about to attack {} using {} for {} damage", player.getName().getString(), entity.getName().getString(), ItemHelper.getItemKey(weapon), amount);

                return EventResult.interruptFalse();
            }
        }

        return EventResult.pass();
    }

    private EventResult beforeMine(Level level, BlockPos pos, BlockState state, ServerPlayer player, @Nullable IntValue xp) {
        var tool = ItemHelper.getItem(player.getMainHandItem());

        if (Restrictions.INSTANCE.isUsable(player, tool, pos)) {
            return EventResult.pass();
        }

        ItemSkills.LOGGER.debug("{} was about to mine {} using {}", player.getName().getString(), ItemHelper.getItemKey(tool), state.getBlock().getName().getString());

        return EventResult.interruptFalse();
    }

    private EventResult beforeInteractEntity(Player player, Entity entity, InteractionHand hand) {
        var tool = ItemHelper.getItemUsed(player, hand);

        if (Restrictions.INSTANCE.isUsable(player, tool, null)) {
            return EventResult.pass();
        }

        ItemSkills.LOGGER.debug("{} was about to interact with entity {} using {}", player.getName().getString(), entity.getName().getString(), ItemHelper.getItemKey(tool));

        return EventResult.interruptFalse();
    }

    private CompoundEventResult<ItemStack> beforeUseItem(Player player, InteractionHand hand) {
        var tool = ItemHelper.getItemUsed(player, hand);

        if (Restrictions.INSTANCE.isUsable(player, tool, null)) {
            return CompoundEventResult.pass();
        }

        ItemSkills.LOGGER.debug("{} is about to use {}", player.getName().getString(), ItemHelper.getItemKey(tool));

        return CompoundEventResult.interruptFalse(null);
    }

    private EventResult beforeUseItemBlock(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        var tool = ItemHelper.getItemUsed(player, hand);

        if (ItemHelper.isEmptyItem(tool) || Restrictions.INSTANCE.isUsable(player, tool, pos)) {
            return EventResult.pass();
        }

        ItemSkills.LOGGER.debug("{} is about to interact with block {} using {}", player.getName().getString(), player.level.getBlockState(pos).getBlock().getName().getString(), ItemHelper.getItemKey(tool));

        return EventResult.interruptFalse();
    }
}
