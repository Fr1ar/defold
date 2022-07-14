// Copyright 2020-2022 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.pipeline;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import javax.imageio.ImageIO;

import org.apache.commons.io.FilenameUtils;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.util.TimeProfiler;
import com.dynamo.bob.textureset.TextureSetGenerator;
import com.dynamo.bob.textureset.TextureSetGenerator.AnimDesc;
import com.dynamo.bob.textureset.TextureSetGenerator.AnimIterator;
import com.dynamo.bob.textureset.TextureSetGenerator.TextureSetResult;
import com.dynamo.gamesys.proto.AtlasProto.Atlas;
import com.dynamo.gamesys.proto.AtlasProto.AtlasAnimation;
import com.dynamo.gamesys.proto.AtlasProto.AtlasImage;
import com.dynamo.gamesys.proto.AtlasProto.AtlasPage;
import com.dynamo.gamesys.proto.Tile.Playback;
import com.dynamo.gamesys.proto.Tile.SpriteTrimmingMode;

public class AtlasUtil {
    public static class MappedAnimDesc extends AnimDesc {
        List<String> ids;

        public MappedAnimDesc(String id, List<String> ids, Playback playback, int fps, boolean flipHorizontal,
                boolean flipVertical) {
            super(id, playback, fps, flipHorizontal, flipVertical);
            this.ids = ids;
        }

        public MappedAnimDesc(String id, List<String> ids) {
            super(id, Playback.PLAYBACK_NONE, 0, false, false);
            this.ids = ids;
        }

        public List<String> getIds() {
            return this.ids;
        }
    }

    public static class MappedAnimIterator implements AnimIterator {
        final List<MappedAnimDesc> anims;
        final List<String> imageIds;
        int nextAnimIndex;
        int nextFrameIndex;

        public MappedAnimIterator(List<MappedAnimDesc> anims, List<String> imageIds) {
            this.anims = anims;
            this.imageIds = imageIds;
        }

        @Override
        public AnimDesc nextAnim() {
            if (nextAnimIndex < anims.size()) {
                nextFrameIndex = 0;
                return anims.get(nextAnimIndex++);
            }
            return null;
        }

        @Override
        public Integer nextFrameIndex() {
            MappedAnimDesc anim = anims.get(nextAnimIndex - 1);
            if (nextFrameIndex < anim.getIds().size()) {
                return imageIds.indexOf(anim.getIds().get(nextFrameIndex++));
            }
            return null;
        }

        @Override
        public void rewind() {
            nextAnimIndex = 0;
            nextFrameIndex = 0;
        }
    }

    private static final class AtlasImageSortKey {
        public final String path;
        public final SpriteTrimmingMode mode;
        public AtlasImageSortKey(String path, SpriteTrimmingMode mode) {
            this.path = path;
            this.mode = mode;
        }
        @Override
        public int hashCode() {
            return path.hashCode() + 31 * this.mode.hashCode();
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            AtlasImageSortKey b = (AtlasImageSortKey)o;
            return this.mode == b.mode && this.path.equals(b.path);
        }
    }

    public static List<AtlasImage> collectImages(Atlas atlas) {
        Map<AtlasImageSortKey, AtlasImage> uniqueImages = new HashMap<AtlasImageSortKey, AtlasImage>();
        List<AtlasImage> images = new ArrayList<AtlasImage>();
        for (AtlasImage image : atlas.getImagesList()) {
            AtlasImageSortKey key = new AtlasImageSortKey(image.getImage(), image.getSpriteTrimMode());
            if (!uniqueImages.containsKey(key)) {
                uniqueImages.put(key, image);
                images.add(image);
            }
        }

        for (AtlasAnimation anim : atlas.getAnimationsList()) {
            for (AtlasImage image : anim.getImagesList() ) {
                AtlasImageSortKey key = new AtlasImageSortKey(image.getImage(), image.getSpriteTrimMode());
                if (!uniqueImages.containsKey(key)) {
                    uniqueImages.put(key, image);
                    images.add(image);
                }
            }
        }
        return images;
    }

    public static List<AtlasImage> collectImagesFromPage(AtlasPage atlasPage) {
        Map<AtlasImageSortKey, AtlasImage> uniqueImages = new HashMap<AtlasImageSortKey, AtlasImage>();
        List<AtlasImage> images = new ArrayList<AtlasImage>();
        for (AtlasImage image : atlasPage.getImagesList()) {
            AtlasImageSortKey key = new AtlasImageSortKey(image.getImage(), image.getSpriteTrimMode());
            if (!uniqueImages.containsKey(key)) {
                uniqueImages.put(key, image);
                images.add(image);
            }
        }

        for (AtlasAnimation anim : atlasPage.getAnimationsList()) {
            for (AtlasImage image : anim.getImagesList() ) {
                AtlasImageSortKey key = new AtlasImageSortKey(image.getImage(), image.getSpriteTrimMode());
                if (!uniqueImages.containsKey(key)) {
                    uniqueImages.put(key, image);
                    images.add(image);
                }
            }
        }
        return images;
    }

    private static String pathToId(String path) {
        return FilenameUtils.removeExtension(FilenameUtils.getName(path));
    }

    private static List<IResource> toResources(IResource baseResource, List<String> paths) {
        List<IResource> resources = new ArrayList<IResource>(paths.size());
        for (String path : paths) {
            resources.add(baseResource.getResource(path));
        }
        return resources;
    }

    public static List<BufferedImage> loadImages(List<IResource> resources) throws IOException, CompileExceptionError {
        List<BufferedImage> images = new ArrayList<BufferedImage>(resources.size());

        for (IResource resource : resources) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(resource.getContent()));
            if (image == null) {
                throw new CompileExceptionError(resource, -1, "Unable to load image " + resource.getPath());
            }
            images.add(image);
        }
        return images;
    }

    private interface PathTransformer {
        String transform(String path);
    }

    private static List<MappedAnimDesc> createAnimDescs(Atlas atlas, PathTransformer transformer) {
        List<MappedAnimDesc> animDescs = new ArrayList<MappedAnimDesc>(atlas.getAnimationsCount()
                + atlas.getImagesCount());
        for (AtlasAnimation anim : atlas.getAnimationsList()) {
            List<String> frameIds = new ArrayList<String>();
            for (AtlasImage image : anim.getImagesList()) {
                frameIds.add(transformer.transform(image.getImage()));
            }
            animDescs.add(new MappedAnimDesc(anim.getId(), frameIds, anim.getPlayback(), anim.getFps(), anim
                    .getFlipHorizontal() != 0, anim.getFlipVertical() != 0));
        }
        for (AtlasImage image : atlas.getImagesList()) {
            MappedAnimDesc animDesc = new MappedAnimDesc(pathToId(image.getImage()), Collections.singletonList(transformer.transform(image.getImage())));
            animDescs.add(animDesc);
        }
        return animDescs;
    }

    private static List<MappedAnimDesc> createAnimDescsFromPage(AtlasPage page, PathTransformer transformer)
    {
        List<MappedAnimDesc> animDescs = new ArrayList<MappedAnimDesc>(page.getAnimationsCount()
                + page.getImagesCount());

        for (AtlasAnimation anim : page.getAnimationsList()) {
            List<String> frameIds = new ArrayList<String>();
            for (AtlasImage image : anim.getImagesList()) {
                frameIds.add(transformer.transform(image.getImage()));
            }
            animDescs.add(new MappedAnimDesc(anim.getId(), frameIds, anim.getPlayback(), anim.getFps(), anim
                    .getFlipHorizontal() != 0, anim.getFlipVertical() != 0));
        }

        for (AtlasImage image : page.getImagesList()) {
            MappedAnimDesc animDesc = new MappedAnimDesc(pathToId(image.getImage()), Collections.singletonList(transformer.transform(image.getImage())));
            animDescs.add(animDesc);
        }

        return animDescs;
    }

    private static int spriteTrimModeToInt(SpriteTrimmingMode mode) {
        switch (mode) {
            case SPRITE_TRIM_MODE_OFF:   return 0;
            case SPRITE_TRIM_MODE_4:     return 4;
            case SPRITE_TRIM_MODE_5:     return 5;
            case SPRITE_TRIM_MODE_6:     return 6;
            case SPRITE_TRIM_MODE_7:     return 7;
            case SPRITE_TRIM_MODE_8:     return 8;
        }
        return 0;
    }

    private static TextureSetResult generateTextureSetFromImages(Atlas atlas, IResource atlasResource, List<AtlasImage> imageList, List<MappedAnimDesc> animDescs, PathTransformer transformer, int pageIndex) throws IOException, CompileExceptionError {
        List<String> imagePaths      = new ArrayList<String>();
        List<Integer> imageHullSizes = new ArrayList<Integer>();

        for (AtlasImage image : imageList) {
            imagePaths.add(image.getImage());
            imageHullSizes.add(spriteTrimModeToInt(image.getSpriteTrimMode()));
        }

        List<IResource> imageResources = toResources(atlasResource, imagePaths);
        List<BufferedImage> images     = AtlasUtil.loadImages(imageResources);

        for (int i = 0; i < imagePaths.size(); ++i) {
            imagePaths.set(i, transformer.transform(imagePaths.get(i)));
        }
        MappedAnimIterator iterator = new MappedAnimIterator(animDescs, imagePaths);

        return TextureSetGenerator.generate(images, imageHullSizes, imagePaths, iterator,
                Math.max(0, atlas.getMargin()),
                Math.max(0, atlas.getInnerPadding()),
                Math.max(0, atlas.getExtrudeBorders()),
                pageIndex, true, false, null);
    }

    public static List<TextureSetResult> generateTextureSet(final Project project, IResource atlasResource) throws IOException, CompileExceptionError {
        TimeProfiler.start("generateTextureSet");
        List<TextureSetResult> result_list = new ArrayList<TextureSetResult>();
        Atlas.Builder builder = Atlas.newBuilder();
        ProtoUtil.merge(atlasResource, builder);
        Atlas atlas = builder.build();

        PathTransformer transformer = new PathTransformer() {
            @Override
            public String transform(String path) {
                return project.getResource(path).getPath();
            }
        };

        // Generate texture set from non-paged images
        TextureSetResult atlasTextureSetResult = generateTextureSetFromImages(atlas, atlasResource,
            collectImages(atlas), createAnimDescs(atlas, transformer), transformer, -1);

        result_list.add(atlasTextureSetResult);

        int pageIndex = 0;
        // Generate texture set from paged images
        for (AtlasPage page : atlas.getPagesList()) {
            TextureSetResult atlasPageTextureSetResult = generateTextureSetFromImages(atlas, atlasResource,
                collectImagesFromPage(page), createAnimDescsFromPage(page, transformer), transformer, pageIndex++);

            result_list.add(atlasPageTextureSetResult);
        }

        TimeProfiler.stop();
        return result_list;
    }
}
