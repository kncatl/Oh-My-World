package com.kncatl.flatpattern.mixin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import com.kncatl.flatpattern.PatternData;
import com.kncatl.flatpattern.PatternFlatSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlatLevelSource.class)
public class MixinFlatLevelSource {

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void onFillFromNoise(Blender blender, RandomState randomState,
                                  net.minecraft.world.level.StructureManager structureManager,
                                  ChunkAccess chunk,
                                  CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if ((Object) this instanceof PatternFlatSource) return;
        if (!PatternData.isActive()) return;

        List<Object> layers = PatternData.get();
        if (layers == null || layers.isEmpty()) return;

        PatternData.fillChunk(chunk, layers);
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }
}
