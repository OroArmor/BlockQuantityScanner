package com.oroarmor.block_quantity_scanner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.MinecraftVersion;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

public class BlockQuantityScanner implements ModInitializer {

    public static Block[] STONE_BLOCKS = addBlocksToArray("stone", "deepslate", "grimstone", "andesite", "granite", "diorite", "air");

    public static Block[] DIAMOND_ORES = addBlocksToArray("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE");
    public static Block[] REDSTONE_ORES = addBlocksToArray("REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE");
    public static Block[] GOLD_ORES = addBlocksToArray("GOLD_ORE", "DEEPSLATE_GOLD_ORE");
    public static Block[] IRON_ORES = addBlocksToArray("IRON_ORE", "DEEPSLATE_IRON_ORE");
    public static Block[] LAPIS_ORES = addBlocksToArray("LAPIS_ORE", "DEEPSLATE_LAPIS_ORE");
    public static Block[] COAL_ORES = addBlocksToArray("COAL_ORE");
    public static Block[] COPPER_ORES = addBlocksToArray("COPPER_ORE");
    public static Block[] EMERALD_ORES = addBlocksToArray("EMERALD_ORE");
    public static final Block[][] ORES = {DIAMOND_ORES, REDSTONE_ORES, GOLD_ORES, IRON_ORES, LAPIS_ORES, COAL_ORES, COPPER_ORES, EMERALD_ORES};
    public static final String[] ORE_NAMES = {"diamond", "redstone", "gold", "lapis", "iron", "copper", "coal", "emerald"};

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(BlockQuantityScanner::registerCommand);
            // /execute positioned 0 100 0 run scan_all_ores 2048 140
    }

    private static Block[] addBlocksToArray(String... blocks) {
        List<Block> blocksList = new ArrayList<>(blocks.length);
        Block defaultBlock = Registry.BLOCK.get(Registry.BLOCK.getDefaultId());
        for (String blockName : blocks) {
            Block block = Registry.BLOCK.get(new Identifier(blockName.toLowerCase()));
            if (block != defaultBlock) {
                blocksList.add(block);
            }
        }
        Object[] objects = blocksList.toArray();
        Block[] array = new Block[objects.length];
        for (int i = 0; i < objects.length; i++) {
            array[i] = (Block) objects[i];
        }
        return array;
    }

    private static void registerCommand(CommandDispatcher<ServerCommandSource> commandSourceCommandDispatcher, boolean dedicated) {
        LiteralArgumentBuilder<ServerCommandSource> scanBlockCommand = LiteralArgumentBuilder.<ServerCommandSource>literal("scan_blocks")
                .requires((serverCommandSource) -> true)
                .then(CommandManager.argument("range", IntegerArgumentType.integer(0, 2048))
                        .then(CommandManager.argument("block", BlockStateArgumentType.blockState())
                                .executes(context -> {
                                    BlockState state = BlockStateArgumentType.getBlockState(context, "block").getBlockState();
                                    int range = IntegerArgumentType.getInteger(context, "range");

                                    return scanBlocks(context, state, range, -1);
                                })
                                .then(CommandManager.argument("max-height", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            BlockState state = BlockStateArgumentType.getBlockState(context, "block").getBlockState();
                                            int range = IntegerArgumentType.getInteger(context, "range");
                                            int maxHeight = IntegerArgumentType.getInteger(context, "max-height");

                                            return scanBlocks(context, state, range, maxHeight);
                                        }))));

        LiteralArgumentBuilder<ServerCommandSource> allOresCommand = LiteralArgumentBuilder.<ServerCommandSource>literal("scan_all_ores")
                .requires((serverCommandSource) -> true)
                .then(CommandManager.argument("range", IntegerArgumentType.integer(0, 2048))
                        .executes(context -> {
                            int range = IntegerArgumentType.getInteger(context, "range");

                            return scanOres(context, range, -1);
                        }).then(CommandManager.argument("max-height", IntegerArgumentType.integer())
                                .executes(context -> {
                                    int range = IntegerArgumentType.getInteger(context, "range");
                                    int maxHeight = IntegerArgumentType.getInteger(context, "max-height");

                                    return scanOres(context, range, maxHeight);
                                })));

        commandSourceCommandDispatcher.register(scanBlockCommand);
        commandSourceCommandDispatcher.register(allOresCommand);
    }

    private static int scanBlocks(CommandContext<ServerCommandSource> context, BlockState state, int range, int maxHeight) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();

        source.sendFeedback(new LiteralText("Starting Scan. This may take several minutes"), true);
        Runnable scanBlocksRunnable = () -> {
            BlockPos executionLocation = new BlockPos(source.getPosition());
            int halfRange = range >> 1;

            int endY = maxHeight == -1 ? world.getTopY() : maxHeight;
            int bottomY = world.getBottomY();
            int yRange = endY - bottomY;

            int[] levelsToCountsArr = new int[(endY - bottomY)];

            int cpuThreads = Runtime.getRuntime().availableProcessors();
            Thread[] threads = new Thread[cpuThreads];

            Block block = state.getBlock();

            Consumer<BlockPos> positionConsumer = pos -> {
                BlockState worldBlockState = world.getBlockState(pos);
                int position = (pos.getY() - bottomY);
                if (worldBlockState.isOf(block)) {
                    levelsToCountsArr[position]++;
                }
                world.setChunkForced(pos.getX() / 16, pos.getZ() / 16, false);
            };

            for (int i = 0; i < cpuThreads; i++) {
                int finalI = i;
                Runnable runnable = () -> {
                    for (int x = -halfRange; x < halfRange; x++) {
                        for (int z = -halfRange; z < halfRange; z++) {
                            for (int y = 0; y < yRange / cpuThreads; y++) {
                                positionConsumer.accept(new BlockPos(x + executionLocation.getX(), y + yRange / cpuThreads * finalI + bottomY, z + executionLocation.getZ()));
                            }
                        }
                    }
                    System.out.printf("Thread %d has finished\n", finalI);
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

            String pattern = "HH-mm-ss-SSS";
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
            String date = simpleDateFormat.format(new Date());
            String fileName = String.format("scan-%s-%s-location-%d-%d-range-%d-%s.csv", MinecraftVersion.GAME_VERSION.getName(), date, executionLocation.getX(), executionLocation.getZ(), range, Registry.BLOCK.getId(block).toString().replace(':', '-'));

            File outputFolder = FabricLoader.getInstance().getGameDir().resolve("block-quantity-scanner").toFile();

            outputFolder.mkdirs();

            File output = outputFolder.toPath().resolve(fileName).toFile();
            try {
                output.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try (FileOutputStream stream = new FileOutputStream(output)) {
                PrintStream printStream = new PrintStream(stream);
                printStream.print("y");
                printStream.print(", " + Registry.BLOCK.getId(block));
                printStream.println();

                for (int y = bottomY; y < endY; y++) {
                    printStream.print(y);
                    printStream.print(", " + levelsToCountsArr[(y - bottomY)]);
                    printStream.println();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            source.sendFeedback(new LiteralText("Scan saved to " + fileName), true);
        };

        Thread scanBlocksThread = new Thread(scanBlocksRunnable);
        scanBlocksThread.start();

        return 1;
    }

    private static int scanOres(CommandContext<ServerCommandSource> context, int range, int maxHeight) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();

        source.sendFeedback(new LiteralText("Starting Scan. This may take several minutes"), true);
        Runnable scanBlocksRunnable = () -> {
            BlockPos executionLocation = new BlockPos(source.getPosition());
            int halfRange = range >> 1;

            int endY = maxHeight == -1 ? world.getTopY() : maxHeight;
            int bottomY = world.getBottomY();
            int yRange = endY - bottomY;

            int[] levelsToCountsArr = new int[(endY - bottomY) * ORES.length];

            int cpuThreads = Runtime.getRuntime().availableProcessors();
            Thread[] threads = new Thread[cpuThreads];

            Consumer<BlockPos> positionConsumer = pos -> {
                BlockState worldBlockState = world.getBlockState(pos);
                Block worldBlock = worldBlockState.getBlock();
                for (Block block : STONE_BLOCKS) {
                    if (worldBlock == block)
                        return;
                }

                int position = (pos.getY() - bottomY) * ORES.length;
                for (int i = 0; i < ORES.length; i++) {
                    for (Block block : ORES[i]) {
                        if (worldBlock == block) {
                            levelsToCountsArr[position + i]++;
                        }
                    }
                }
            };

            for (int i = 0; i < cpuThreads; i++) {
                int finalI = i;
                Runnable runnable = () -> {
                    for (int x = -halfRange; x < halfRange; x++) {
                        for (int z = -halfRange; z < halfRange; z++) {
                            for (int y = 0; y < yRange / cpuThreads; y++) {
                                positionConsumer.accept(new BlockPos(x + executionLocation.getX(), y + yRange / cpuThreads * finalI + bottomY, z + executionLocation.getZ()));
                            }
                        }
                    }
                    System.out.printf("Thread %d has finished\n", finalI);
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

            String pattern = "HH-mm-ss-SSS";
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
            String date = simpleDateFormat.format(new Date());
            String fileName = String.format("scan-%s-%s-location-%d-%d-range-%d-ores.csv", MinecraftVersion.GAME_VERSION.getName(), date, executionLocation.getX(), executionLocation.getZ(), range);

            File outputFolder = FabricLoader.getInstance().getGameDir().resolve("block-quantity-scanner").toFile();

            outputFolder.mkdirs();

            File output = outputFolder.toPath().resolve(fileName).toFile();
            try {
                output.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try (FileOutputStream stream = new FileOutputStream(output)) {
                PrintStream printStream = new PrintStream(stream);
                printStream.print("y");
                for (String name : ORE_NAMES) {
                    printStream.print(", " + name);
                }
                printStream.println();

                for (int y = bottomY; y < endY; y++) {
                    printStream.print(y);
                    for (int i = 0; i < ORES.length; i++) {
                        printStream.print(", " + levelsToCountsArr[(y - bottomY) * ORES.length + i]);
                    }
                    printStream.println();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            source.sendFeedback(new LiteralText("Scan saved to " + fileName), true);
        };

        Thread scanBlocksThread = new Thread(scanBlocksRunnable);
        scanBlocksThread.start();
        try {
            scanBlocksThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return 1;
    }
}
