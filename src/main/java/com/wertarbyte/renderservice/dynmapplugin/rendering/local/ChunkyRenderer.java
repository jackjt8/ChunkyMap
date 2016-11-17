package com.wertarbyte.renderservice.dynmapplugin.rendering.local;

import com.wertarbyte.renderservice.dynmapplugin.rendering.FileBufferRenderContext;
import com.wertarbyte.renderservice.dynmapplugin.rendering.RenderException;
import com.wertarbyte.renderservice.dynmapplugin.rendering.Renderer;
import com.wertarbyte.renderservice.dynmapplugin.rendering.SilentTaskTracker;
import se.llbit.chunky.renderer.RenderManager;
import se.llbit.chunky.renderer.SnapshotControl;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.renderer.scene.SynchronousSceneManager;
import se.llbit.chunky.resources.BitmapImage;
import se.llbit.chunky.resources.TexturePackLoader;
import se.llbit.util.TaskTracker;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A renderer that uses Chunky to render scenes locally.
 */
public class ChunkyRenderer implements Renderer {
    private final int targetSpp;
    private final int threads;

    public ChunkyRenderer(int targetSpp, int threads) {
        this.targetSpp = targetSpp;
        this.threads = threads;
    }

    @Override
    public CompletableFuture<BufferedImage> render(FileBufferRenderContext context, File texturepack, Consumer<Scene> initializeScene) {
        CompletableFuture<BufferedImage> result = new CompletableFuture<>();

        try {
            TexturePackLoader.loadTexturePack(texturepack, false); // TODO this means that only one texturepack can be used for all maps
        } catch (TexturePackLoader.TextureLoadingError e) {
            result.completeExceptionally(new RenderException("Could not load texturepack", e));
            return result;
        }

        se.llbit.chunky.renderer.Renderer renderer = new RenderManager(context, true);
        SynchronousSceneManager sceneManager = new SynchronousSceneManager(context, renderer);
        initializeScene.accept(sceneManager.getScene());
        renderer.setSceneProvider(sceneManager);
        renderer.setSnapshotControl(new SnapshotControl() {
            @Override
            public boolean saveSnapshot(Scene scene, int nextSpp) {
                return false;
            }

            @Override
            public boolean saveRenderDump(Scene scene, int nextSpp) {
                return false;
            }
        });
        renderer.setNumThreads(threads);
        renderer.setOnRenderCompleted((time, sps) -> {
            try {
                result.complete(getImage(sceneManager.getScene()));
            } catch (ReflectiveOperationException e) {
                result.completeExceptionally(new RenderException("Could not get final image from Chunky", e));
            }
        });

        try {
            sceneManager.getScene().setTargetSpp(targetSpp);
            sceneManager.getScene().startHeadlessRender();
            renderer.start();
            renderer.join();
        } catch (InterruptedException e) {
            result.completeExceptionally(new RenderException("Rendering failed", e));
        } finally {
            renderer.shutdown();
        }

        return result;
    }

    private BufferedImage getImage(Scene scene) throws ReflectiveOperationException {
        Class<Scene> sceneClass = Scene.class;
        Method computeAlpha = sceneClass.getDeclaredMethod("computeAlpha", TaskTracker.class);
        computeAlpha.setAccessible(true);
        computeAlpha.invoke(scene, SilentTaskTracker.INSTANCE);

        Field finalized = sceneClass.getDeclaredField("finalized");
        finalized.setAccessible(true);
        if (!finalized.getBoolean(scene)) {
            scene.postProcessFrame(SilentTaskTracker.INSTANCE);
        }

        Field backBuffer = sceneClass.getDeclaredField("backBuffer");
        backBuffer.setAccessible(true);
        BitmapImage bitmap = (BitmapImage) backBuffer.get(scene);

        BufferedImage renderedImage = new BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.height; y++) {
            for (int x = 0; x < bitmap.width; x++) {
                renderedImage.setRGB(x, y, bitmap.getPixel(x, y));
            }
        }
        return renderedImage;
    }
}