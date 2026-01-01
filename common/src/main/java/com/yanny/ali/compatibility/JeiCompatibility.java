package com.yanny.ali.compatibility;

import com.mojang.logging.LogUtils;
import com.yanny.ali.Utils;
import com.yanny.ali.compatibility.common.*;
import com.yanny.ali.compatibility.jei.*;
import com.yanny.ali.configuration.AliConfig;
import com.yanny.ali.configuration.LootCategory;
import com.yanny.ali.manager.AliClientRegistry;
import com.yanny.ali.manager.PluginManager;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@JeiPlugin
public class JeiCompatibility implements IModPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<LootCategory<Block>, JeiBlockLoot> blockCategories = new LinkedHashMap<>();
    private final Map<LootCategory<EntityType<?>>, JeiEntityLoot> entityCategories = new LinkedHashMap<>();
    private final Map<LootCategory<ResourceLocation>, JeiGameplayLoot> gameplayCategories = new LinkedHashMap<>();
    private final Map<LootCategory<ResourceLocation>, JeiTradeLoot> tradeCategories = new LinkedHashMap<>();
    private final AtomicBoolean recipesRegistered = new AtomicBoolean(false);
    private final AtomicReference<byte[]> pendingData = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<byte[]>> deferredFuture = new AtomicReference<>();
    private volatile IJeiRuntime jeiRuntime;

    @Override
    public void onRuntimeUnavailable() {
        blockCategories.clear();
        entityCategories.clear();
        gameplayCategories.clear();
        tradeCategories.clear();
        jeiRuntime = null;
        pendingData.set(null);
        deferredFuture.set(null);
        recipesRegistered.set(false);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        this.jeiRuntime = jeiRuntime;
        tryRegisterDeferred();
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        AliConfig config = PluginManager.COMMON_REGISTRY.getConfiguration();
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();

        blockCategories.clear();
        entityCategories.clear();
        gameplayCategories.clear();
        tradeCategories.clear();

        blockCategories.putAll(config.blockCategories.stream().collect(getCollector(guiHelper, JeiBlockLoot::new)));
        entityCategories.putAll(config.entityCategories.stream().collect(getCollector(guiHelper, JeiEntityLoot::new)));
        gameplayCategories.putAll(config.gameplayCategories.stream().collect(getCollector(guiHelper, JeiGameplayLoot::new)));
        tradeCategories.putAll(config.tradeCategories.stream().collect(getCollector(guiHelper, JeiTradeLoot::new)));

        blockCategories.values().forEach(registration::addRecipeCategories);
        entityCategories.values().forEach(registration::addRecipeCategories);
        gameplayCategories.values().forEach(registration::addRecipeCategories);
        tradeCategories.values().forEach(registration::addRecipeCategories);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        CompletableFuture<byte[]> futureData = PluginManager.CLIENT_REGISTRY.getCurrentDataFuture();

        if (!futureData.isDone() || futureData.isCompletedExceptionally() || futureData.isCancelled()) {
            LOGGER.info("Data not ready, deferring JEI recipe registration.");
            scheduleDeferredRegistration(futureData);
            return;
        }

        LOGGER.info("Data already received, processing instantly.");

        try {
            byte[] fullCompressedData = futureData.get();

            registerData(registration::addRecipes, fullCompressedData);
            recipesRegistered.set(true);
            LOGGER.info("Data registration finished successfully.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;

            if (cause instanceof TimeoutException || cause instanceof CancellationException) {
                LOGGER.error("Failed to receive data: Operation aborted or timed out. Registration aborted!");
            } else {
                LOGGER.error("Failed to finish registering data with error {}", cause.getMessage());
                cause.printStackTrace();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Registration thread interrupted!");
        } catch (Throwable e) {
            e.printStackTrace();
            LOGGER.error("Failed to finish registering data with unexpected error {}", e.getMessage());
        }
    }

    private void registerData(RecipeRegistrar registrar, byte[] fullCompressedData) {
        AliClientRegistry clientRegistry = PluginManager.CLIENT_REGISTRY;
        AliConfig config = PluginManager.COMMON_REGISTRY.getConfiguration();
        ClientLevel level = Minecraft.getInstance().level;

        LOGGER.info("Adding loot information to JEI");

        if (level != null) {
            Map<RecipeType<RecipeHolder<BlockLootType>>, List<BlockLootType>> blockRecipeTypes = new HashMap<>();
            Map<RecipeType<RecipeHolder<EntityLootType>>, List<EntityLootType>> entityRecipeTypes = new HashMap<>();
            Map<RecipeType<RecipeHolder<GameplayLootType>>, List<GameplayLootType>> gameplayRecipeTypes = new HashMap<>();
            Map<RecipeType<RecipeHolder<TradeLootType>>, List<TradeLootType>> tradeRecipeTypes = new HashMap<>();

            GenericUtils.processData(
                    level,
                    clientRegistry,
                    config,
                    fullCompressedData,
                    (node, location, block, outputs) -> {
                        RecipeType<RecipeHolder<BlockLootType>> recipeType = null;

                        for (JeiBlockLoot recipeCategory : blockCategories.values()) {
                            if (recipeCategory.getLootCategory().validate(block)) {
                                if (recipeCategory.getLootCategory().isHidden()) {
                                    return;
                                }

                                recipeType = recipeCategory.getRecipeType();
                                break;
                            }
                        }

                        if (recipeType != null) {
                            blockRecipeTypes.computeIfAbsent(recipeType, (p) -> new LinkedList<>()).add(new BlockLootType(block, node, Collections.emptyList(), outputs));
                        }
                    },
                    (node, location, entity, outputs) -> {
                        RecipeType<RecipeHolder<EntityLootType>> recipeType = null;

                        for (JeiEntityLoot recipeCategory : entityCategories.values()) {
                            if (recipeCategory.getLootCategory().validate(entity)) {
                                if (recipeCategory.getLootCategory().isHidden()) {
                                    return;
                                }

                                recipeType = recipeCategory.getRecipeType();
                                break;
                            }
                        }

                        if (recipeType != null) {
                            entityRecipeTypes.computeIfAbsent(recipeType, (p) -> new LinkedList<>()).add(new EntityLootType(entity, location, node, Collections.emptyList(), outputs));
                        }
                    },
                    (node, location, outputs) -> {
                        RecipeType<RecipeHolder<GameplayLootType>> recipeType = null;

                        for (JeiGameplayLoot recipeCategory : gameplayCategories.values()) {
                            if (recipeCategory.getLootCategory().validate(location)) {
                                if (recipeCategory.getLootCategory().isHidden()) {
                                    return;
                                }

                                recipeType = recipeCategory.getRecipeType();
                                break;
                            }
                        }

                        if (recipeType != null) {
                            gameplayRecipeTypes.computeIfAbsent(recipeType, (p) -> new LinkedList<>()).add(new GameplayLootType(node, location, Collections.emptyList(), outputs));
                        }
                    },
                    (node, location, inputs, outputs) -> {
                        RecipeType<RecipeHolder<TradeLootType>> recipeType = null;

                        for (JeiTradeLoot recipeCategory : tradeCategories.values()) {
                            if (recipeCategory.getLootCategory().validate(location)) {
                                if (recipeCategory.getLootCategory().isHidden()) {
                                    return;
                                }

                                recipeType = recipeCategory.getRecipeType();
                                break;
                            }
                        }

                        if (recipeType != null) {
                            tradeRecipeTypes.computeIfAbsent(recipeType, (p) -> new LinkedList<>()).add(new TradeLootType(node, location.getPath(), inputs, outputs));
                        }
                    },
                    (node, location, inputs, outputs) -> {
                        RecipeType<RecipeHolder<TradeLootType>> recipeType = null;

                        for (JeiTradeLoot recipeCategory : tradeCategories.values()) {
                            if (recipeCategory.getLootCategory().validate(location)) {
                                if (recipeCategory.getLootCategory().isHidden()) {
                                    return;
                                }

                                recipeType = recipeCategory.getRecipeType();
                                break;
                            }
                        }

                        if (recipeType != null) {
                            tradeRecipeTypes.computeIfAbsent(recipeType, (p) -> new LinkedList<>()).add(new TradeLootType(node, location.getPath(), inputs, outputs));
                        }
                    }
            );

            for (Map.Entry<RecipeType<RecipeHolder<BlockLootType>>, List<BlockLootType>> entry : blockRecipeTypes.entrySet()) {
                registrar.addRecipes(entry.getKey(), entry.getValue().stream().map(RecipeHolder::new).toList());
            }

            for (Map.Entry<RecipeType<RecipeHolder<EntityLootType>>, List<EntityLootType>> entry : entityRecipeTypes.entrySet()) {
                registrar.addRecipes(entry.getKey(), entry.getValue().stream().map(RecipeHolder::new).toList());
            }

            for (Map.Entry<RecipeType<RecipeHolder<GameplayLootType>>, List<GameplayLootType>> entry : gameplayRecipeTypes.entrySet()) {
                registrar.addRecipes(entry.getKey(), entry.getValue().stream().map(RecipeHolder::new).toList());
            }

            for (Map.Entry<RecipeType<RecipeHolder<TradeLootType>>, List<TradeLootType>> entry : tradeRecipeTypes.entrySet()) {
                registrar.addRecipes(entry.getKey(), entry.getValue().stream().map(RecipeHolder::new).toList());
            }
        } else {
            LOGGER.warn("JEI integration was not loaded! Level is null!");
        }
    }

    private void scheduleDeferredRegistration(CompletableFuture<byte[]> futureData) {
        if (futureData == null) {
            return;
        }

        CompletableFuture<byte[]> previous = deferredFuture.getAndSet(futureData);

        if (previous == futureData) {
            return;
        }

        futureData.whenComplete((data, throwable) -> {
            if (throwable != null) {
                logRegistrationFailure(throwable);
                CompletableFuture<byte[]> currentFuture = PluginManager.CLIENT_REGISTRY.getCurrentDataFuture();

                if (!recipesRegistered.get() && currentFuture != futureData) {
                    scheduleDeferredRegistration(currentFuture);
                }

                return;
            }

            pendingData.set(data);
            tryRegisterDeferred();
        });
    }

    private void tryRegisterDeferred() {
        if (recipesRegistered.get()) {
            return;
        }

        IJeiRuntime runtime = this.jeiRuntime;
        byte[] data = pendingData.get();

        if (runtime == null || data == null) {
            return;
        }

        if (!recipesRegistered.compareAndSet(false, true)) {
            return;
        }

        Minecraft.getInstance().execute(() -> {
            try {
                registerData(runtime.getRecipeManager()::addRecipes, data);
                pendingData.set(null);
                LOGGER.info("Data registration finished successfully.");
            } catch (Throwable e) {
                recipesRegistered.set(false);
                logRegistrationFailure(e);
            }
        });
    }

    private void logRegistrationFailure(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;

        if (cause instanceof TimeoutException || cause instanceof CancellationException) {
            LOGGER.error("Failed to receive data: Operation aborted or timed out. Registration aborted!");
        } else if (cause instanceof ExecutionException && cause.getCause() != null) {
            logRegistrationFailure(cause.getCause());
        } else {
            LOGGER.error("Failed to finish registering data with error {}", cause.getMessage());
            cause.printStackTrace();
        }
    }

    private interface RecipeRegistrar {
        <T extends IType> void addRecipes(RecipeType<RecipeHolder<T>> recipeType, List<RecipeHolder<T>> recipes);
    }

    @NotNull
    @Override
    public ResourceLocation getPluginUid() {
        return Utils.modLoc("jei_plugin");
    }

    private static <T, U, V extends IType> T createCategory(IGuiHelper guiHelper, LootCategory<U> e, LootConstructor<T, U, V> constructor) {
        //noinspection unchecked
        RecipeType<RecipeHolder<V>> recipeType = (RecipeType<RecipeHolder<V>>) (Object) RecipeType.create(e.getKey().getNamespace(), e.getKey().getPath(), RecipeHolder.class);
        Component title = Component.translatable("emi.category." + e.getKey().getNamespace() + "." + e.getKey().getPath().replace('/', '.'));
        return constructor.construct(guiHelper, recipeType, e, title, guiHelper.createDrawableItemStack(e.getIcon().getDefaultInstance()));
    }

    @NotNull
    private static  <T, U, V extends IType> Collector<LootCategory<U>, ?, Map<LootCategory<U>, T>> getCollector(IGuiHelper guiHelper, LootConstructor<T, U, V> supplier) {
        return Collectors.toMap(
                (e) -> e,
                (e) -> createCategory(guiHelper, e, supplier),
                (a, b) -> a,
                LinkedHashMap::new
        );
    }

    @FunctionalInterface
    private interface LootConstructor<T, U, V extends IType> {
        T construct(IGuiHelper guiHelper, RecipeType<RecipeHolder<V>> recipeType, LootCategory<U> lootCategory, Component title, IDrawable icon);
    }
}
