package com.oroarmor.block_quantity_scanner;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

public class BlockQuantityScanner implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(BlockQuantityScanner::registerCommand);
    }

    private static void registerCommand(CommandDispatcher<ServerCommandSource> commandSourceCommandDispatcher, boolean dedicated) {
        LiteralArgumentBuilder<ServerCommandSource> scanBlockCommand = LiteralArgumentBuilder.<ServerCommandSource>literal("scan_blocks")
                .requires((serverCommandSource) -> true)
                .then(CommandManager.argument("range", IntegerArgumentType.integer(0, 2048))
                        .then(CommandManager.argument("block", BlockStateArgumentType.blockState())
                                .executes(context -> {
                                    BlockState state = BlockStateArgumentType.getBlockState(context, "block").getBlockState();
                                    int range = IntegerArgumentType.getInteger(context, "range");

                                    return scanBlocks(context, new BlockState[]{state}, range, -1);
                                })
                                .then(CommandManager.argument("max-height", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            BlockState state = BlockStateArgumentType.getBlockState(context, "block").getBlockState();
                                            int range = IntegerArgumentType.getInteger(context, "range");
                                            int maxHeight = IntegerArgumentType.getInteger(context, "max-height");

                                            return scanBlocks(context, new BlockState[]{state}, range, maxHeight);
                                        }))));

        LiteralArgumentBuilder<ServerCommandSource> allOresCommand = LiteralArgumentBuilder.<ServerCommandSource>literal("scan_all_ores")
                .requires((serverCommandSource) -> true)
                .then(CommandManager.argument("range", IntegerArgumentType.integer(0, 2048))
                        .executes(context -> {
                            int range = IntegerArgumentType.getInteger(context, "range");

                            return scanBlocks(context, new BlockState[]{Blocks.DIAMOND_ORE.getDefaultState(), Blocks.GOLD_ORE.getDefaultState(), Blocks.IRON_ORE.getDefaultState(), Blocks.REDSTONE_ORE.getDefaultState(), Blocks.EMERALD_ORE.getDefaultState(), Blocks.COAL_ORE.getDefaultState(), Blocks.COPPER_ORE.getDefaultState(), Blocks.LAPIS_ORE.getDefaultState()},
                                    range, -1);
                        }));

        commandSourceCommandDispatcher.register(scanBlockCommand);
        commandSourceCommandDispatcher.register(allOresCommand);
    }

    private static int scanBlocks(CommandContext<ServerCommandSource> context, BlockState[] states, int range, int maxHeight) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();

        BlockPos executionLocation = new BlockPos(source.getPosition());
        int halfRange = range >> 1;

        int endY = maxHeight == -1 ? world.getTopY() : maxHeight;
        int bottomY = world.getBottomY();
        int yRange = endY - bottomY;

        int[] levelsToCountsArr = new int[(endY - bottomY) * states.length];

        int cpuThreads = Runtime.getRuntime().availableProcessors();
        Thread[] threads = new Thread[cpuThreads];

        Consumer<BlockPos> positionConsumer = pos -> {
            BlockState worldBlockState = world.getBlockState(pos);
            int position = (pos.getY() - bottomY) * states.length;
            for (int i = 0; i < states.length; i++) {
                if (worldBlockState.isOf(states[i].getBlock())) {
                    levelsToCountsArr[position + i]++;
                }
            }
        };

        for (int i = 0; i < cpuThreads; i++) {
            int finalI = i;
            Runnable runnable = () -> {
                for (int y = 0; y < yRange / cpuThreads; y++) {
                    for (int x = -halfRange; x < halfRange; x++) {
                        for (int z = -halfRange; z < halfRange; z++) {
                            positionConsumer.accept(new BlockPos(x + executionLocation.getX(), y + yRange / cpuThreads * finalI + bottomY, z + executionLocation.getZ()));
                        }
                    }
                }
            };
            threads[i] = new Thread(runnable);
            threads[i].start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        System.out.print("y");
        for (BlockState state : states) {
            System.out.print(", " + Registry.BLOCK.getId(state.getBlock()));
        }
        System.out.println();

        for (int y = bottomY; y < endY; y++) {
            System.out.print(y);
            for (int i = 0; i < states.length; i++) {
                System.out.print(", " + levelsToCountsArr[(y - bottomY) * states.length + i]);
            }
            System.out.println();
        }

        return 1;
    }
}
