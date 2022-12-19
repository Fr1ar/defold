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

#include <string.h>
#include <dlib/log.h>
#include "image.h"

//#define STBI_NO_JPEG
//#define STBI_NO_PNG
#define STBI_NO_BMP
#define STBI_NO_PSD
#define STBI_NO_TGA
#define STBI_NO_GIF
//#define STBI_NO_HDR
#define STBI_NO_PIC
#define STBI_NO_PNM
//#define STBI_NO_LINEAR
#define STBI_NO_STDIO
#define STBI_FAILURE_USERMSG
#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_THREAD_LOCALS
#include "../stb/stb_image.h"

namespace dmImage
{
    void Premultiply(uint8_t* buffer, int width, int height)
    {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int index = (y * width + x) * 4;
                uint32_t a = buffer[index + 3];
                uint32_t r = (buffer[index + 0] * a + 255) >> 8;
                uint32_t g = (buffer[index + 1] * a + 255) >> 8;
                uint32_t b = (buffer[index + 2] * a + 255) >> 8;
                buffer[index + 0] = r;
                buffer[index + 1] = g;
                buffer[index + 2] = b;
            }
        }
    }

    static Result LoadLinear(const void* buffer, uint32_t buffer_size, ImageLoadOptions opts, Image* image)
    {
        int x, y, comp;
        float* ret = stbi_loadf_from_memory((const stbi_uc*) buffer, (int) buffer_size, &x, &y, &comp, opts.m_DesiredChannels);

        if (ret)
        {
            Image i;
            i.m_Width  = (uint32_t) x;
            i.m_Height = (uint32_t) y;

            comp = opts.m_DesiredChannels > 0 ? opts.m_DesiredChannels : comp;

            for (int i = 0; i < x * y * comp; ++i)
            {
                if (ret[i] > 1.0)
                    dmLogInfo("%f", ret[i]);
            }

            if (comp == 3)
            {
                i.m_Type = TYPE_RGB32F;
            }
            else if (comp == 4)
            {
                i.m_Type = TYPE_RGBA32F;
            }
            else
            {
                dmLogError("Unexpected number of components in image (%d)", comp);
                free(ret);
                return RESULT_IMAGE_ERROR;
            }

            i.m_Buffer = (void*) ret;
            *image = i;
        }
        else
        {
            dmLogError("Failed to load image: '%s'", stbi_failure_reason());
            return RESULT_IMAGE_ERROR;
        }

        return RESULT_OK;
    }

    static Result Load8Bit(const void* buffer, uint32_t buffer_size, ImageLoadOptions opts, Image* image)
    {
        int x, y, comp;
        unsigned char* ret = stbi_load_from_memory((const stbi_uc*) buffer, (int) buffer_size, &x, &y, &comp, opts.m_DesiredChannels);

        if (ret)
        {
            Image i;
            i.m_Width = (uint32_t) x;
            i.m_Height = (uint32_t) y;
            comp = opts.m_DesiredChannels > 0 ? opts.m_DesiredChannels : comp;

            switch (comp)
            {
                case 1:
                    i.m_Type = TYPE_LUMINANCE;
                    break;
                case 2:
                    // Luminance + alpha. Convert to luminance
                    i.m_Type = TYPE_LUMINANCE;
                    ret = stbi__convert_format(ret, 2, 1, x, y);
                    break;
                case 3:
                    i.m_Type = TYPE_RGB;
                    break;
                case 4:
                    i.m_Type = TYPE_RGBA;
                    if (opts.m_PremultiplyAlpha)
                    {
                        Premultiply(ret, x, y);
                    }
                    break;
                default:
                    dmLogError("Unexpected number of components in image (%d)", comp);
                    free(ret);
                    return RESULT_IMAGE_ERROR;
            }

            i.m_Buffer = (void*) ret;
            *image = i;
        }
        else
        {
            dmLogError("Failed to load image: '%s'", stbi_failure_reason());
            return RESULT_IMAGE_ERROR;
        }

        return RESULT_OK;
    }

    Result Load(const void* buffer, uint32_t buffer_size, bool premult, Image* image)
    {
        ImageLoadOptions opts;
        opts.m_PremultiplyAlpha = premult;
        return Load(buffer, buffer_size, opts, image);
    }

    Result Load(const void* buffer, uint32_t buffer_size, ImageLoadOptions opts, Image* image)
    {
        if (stbi_is_hdr_from_memory((const stbi_uc*) buffer, (int) buffer_size))
        {
            return LoadLinear(buffer, buffer_size, opts, image);
        }
        else
        {
            return Load8Bit(buffer, buffer_size, opts, image);
        }
    }

    void Free(Image* image)
    {
        free(image->m_Buffer);
        memset(image, 0, sizeof(*image));
    }

    uint32_t BytesPerPixel(Type type)
    {
        switch (type)
        {
            case dmImage::TYPE_RGB:       return 3;
            case dmImage::TYPE_RGBA:      return 4;
            case dmImage::TYPE_LUMINANCE: return 1;
            case dmImage::TYPE_RGB32F:    return 3 * sizeof(float);
            case dmImage::TYPE_RGBA32F:   return 4 * sizeof(float);
        }
        return 0;
    }
}

