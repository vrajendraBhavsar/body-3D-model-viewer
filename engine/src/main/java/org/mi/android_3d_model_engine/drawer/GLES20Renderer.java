package org.mi.android_3d_model_engine.drawer;

import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import org.mi.android_3d_model_engine.model.AnimatedModel;
import org.mi.android_3d_model_engine.model.Constants;
import org.mi.android_3d_model_engine.model.Element;
import org.mi.android_3d_model_engine.model.Object3DData;
import org.mi.util.android.GLUtil;
import org.mi.util.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
class GLES20Renderer implements Renderer {

    private static final int COORDS_PER_VERTEX = 3;
    private static final int TEXTURE_COORDS_PER_VERTEX = 2;
    private static final int COLOR_COORDS_PER_VERTEX = 4;

    private final static float[] DEFAULT_COLOR = Constants.COLOR_WHITE;
    private final static float[] NO_COLOR_MASK = Constants.COLOR_WHITE;

    // specification
    private final String id;
    private final Set<String> features;

    // opengl program
    private final int mProgram;

    // animation data
    // put 0 to draw progressively, -1 to draw at once
    private long counter = -1;
    private double shift = -1d;

    // does the device support drawElements for GL_UNSIGNED_INT or not?
    private boolean drawUsingUnsignedInt = true;

    /**
     * Textures cache
     */
    private Map<Object, Integer> textures = new HashMap<>();
    /**
     * Join transform names cache (optimization)
     */
    private final SparseArray<String> cache1 = new SparseArray<>();
    /**
     * Runtime flags
     */
    private static Map<Object, Object> flags = new HashMap<>();

    private boolean texturesEnabled = true;
    private boolean lightingEnabled = true;
    private boolean animationEnabled = true;

    private boolean autoUseProgram = true;

    static GLES20Renderer getInstance(String id, String vertexShaderCode, String fragmentShaderCode) {
        Set<String> shaderFeatures = new HashSet<>();
        testShaderFeature(shaderFeatures, vertexShaderCode, "u_MMatrix");
        testShaderFeature(shaderFeatures, vertexShaderCode, "a_Position");
        testShaderFeature(shaderFeatures, vertexShaderCode, "a_Normal");
        testShaderFeature(shaderFeatures, vertexShaderCode, "a_Color");
        testShaderFeature(shaderFeatures, vertexShaderCode, "a_TexCoordinate");
        testShaderFeature(shaderFeatures, vertexShaderCode, "u_LightPos");
        testShaderFeature(shaderFeatures, vertexShaderCode, "in_jointIndices");
        testShaderFeature(shaderFeatures, vertexShaderCode, "in_weights");
        testShaderFeature(shaderFeatures, fragmentShaderCode, "u_LightPos");
        testShaderFeature(shaderFeatures, fragmentShaderCode, "u_TextureCube");
        return new GLES20Renderer(id, vertexShaderCode, fragmentShaderCode, shaderFeatures);
    }

    private static void testShaderFeature(Set<String> outputFeatures, String shaderCode, String feature) {
        if (shaderCode.contains(feature)) {
            outputFeatures.add(feature);
        }
    }

    private GLES20Renderer(String id, String vertexShaderCode, String fragmentShaderCode, Set<String> features) {

        this.id = id;
        this.features = features;
        Log.i("GLES20Renderer", "Compiling 3D Drawer... " + id);

        // load shaders
        int vertexShader = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        // compile program
        mProgram = GLUtil.createAndLinkProgram(vertexShader, fragmentShader, features.toArray(new String[0]));

        flags.clear();
        Log.d("GLES20Renderer", "Compiled 3D Drawer (" + id + ") with id " + mProgram);
    }

    @Override
    public void setAutoUseProgram(boolean autoUseProgram) {
        this.autoUseProgram = autoUseProgram;
    }

    @Override
    public void useProgram() {
        // Add program to OpenGL environment
        GLES20.glUseProgram(mProgram);
        if (GLUtil.checkGlError("glUseProgram")) {
            //return;
        }
    }

    @Override
    public int getProgram() {
        return mProgram;
    }

    public void setTexturesEnabled(boolean texturesEnabled) {
        this.texturesEnabled = texturesEnabled;
    }

    public void setLightingEnabled(boolean lightingEnabled) {
        this.lightingEnabled = lightingEnabled;
    }

    public void setAnimationEnabled(boolean animationEnabled) {
        this.animationEnabled = animationEnabled;
    }

    @Override
    public void draw(Object3DData obj, float[] pMatrix, float[] vMatrix, int textureId, float[] lightPosInWorldSpace, float[] colorMask, float[] cameraPos, int drawMode, int drawSize) {

        // log event once
        // FIXME: this was flooding the system out!
        /*if (id != flags.get(obj.getId())) {
            //Log.d("GLES20Renderer", "Rendering with shader: " + id + "vert... obj: " + obj);
            flags.put(obj.getId(), this.id);
        }*/

        if (this.autoUseProgram){
            useProgram();
        }

        //setFeatureFlag("u_Debug",false);

        // mvp matrix for position + lighting + animation
        if(supportsMMatrix()) {
            setUniformMatrix4(obj.getModelMatrix(), "u_MMatrix");
        }
        setUniformMatrix4(vMatrix, "u_VMatrix");
        setUniformMatrix4(pMatrix, "u_PMatrix");

        // pass in vertex buffer
        int mPositionHandle = setVBO("a_Position", obj.getVertexBuffer(), COORDS_PER_VERTEX);

        // pass in normals buffer for lighting
        int mNormalHandle = -1;
        if (supportsNormals() && obj.getNormalsBuffer() != null) {
            mNormalHandle = setVBO("a_Normal", obj.getNormalsBuffer(), COORDS_PER_VERTEX);
        }

        // pass in color or colors array
        if (obj.getColor() != null){
            setUniform4(obj.getColor(), "vColor");
        } else {
            setUniform4(DEFAULT_COLOR, "vColor");
        }

        // colors
        int mColorHandle = -1;
        if (supportsColors()){
            setFeatureFlag("u_Coloured",false);
            if (obj.getColorsBuffer() != null) {
                mColorHandle = setVBO("a_Color", obj.getColorsBuffer(), COLOR_COORDS_PER_VERTEX);
                setFeatureFlag("u_Coloured", true);
            }
        }

        // pass in color mask - i.e. stereoscopic
        setUniform4(colorMask != null ? colorMask : NO_COLOR_MASK,"vColorMask");

        // pass in texture UV buffer
        int mTextureHandle = -1;
        if (supportsTextures()) {
            setFeatureFlag("u_Textured", texturesEnabled);

            // load color map
            if (obj.getMaterial().getTextureId() == -1 &&
                    obj.getMaterial().getColorTexture() != null) {

                // bind bitmap
                textureId = GLUtil.loadTexture(obj.getMaterial().getColorTexture());

                obj.getMaterial().setTextureId(textureId);
            }

            // load normal map
            if (obj.getMaterial().getNormalTextureId() == -1 &&
                    obj.getMaterial().getNormalTexture() != null) {

                // log event
                Log.i("ModelRenderer", "Binding normal map... " + obj.getMaterial().getName());

                // bind bitmap
                int handler = GLUtil.loadTexture(obj.getMaterial().getNormalTexture());

                obj.getMaterial().setNormalTextureId(handler);
            }

            // load emissive map
            if (obj.getMaterial().getEmissiveTextureId() == -1 &&
                    obj.getMaterial().getEmissiveTexture() != null) {

                // log event
                Log.i("ModelRenderer", "Binding normal map... " + obj.getMaterial().getName());

                // bind bitmap
                int handler = GLUtil.loadTexture(obj.getMaterial().getEmissiveTexture());

                obj.getMaterial().setEmissiveTextureId(handler);
            }

            if (obj.getMaterial().getTextureId() == -1 && obj.getTextureData() != null) {
                Log.i("ModelRenderer", "Loading texture for obj: '" + obj.getId() + "'... bytes: " + obj.getTextureData().length);
                ByteArrayInputStream textureIs = new ByteArrayInputStream(obj.getTextureData());
                textureId = GLUtil.loadTexture(textureIs);
                try {
                    textureIs.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                obj.getMaterial().setTextureId(textureId);
                //textures.put(obj.getMaterial(), textureId);

                Log.i("ModelRenderer", "Loaded texture OK. id: " + textureId);
            }

            if (obj.getTextureBuffer() != null) {
                mTextureHandle = setVBO("a_TexCoordinate", obj.getTextureBuffer(), TEXTURE_COORDS_PER_VERTEX);


                if (obj.getMaterial().getTextureId() != -1) {
                    setTexture(obj.getMaterial().getTextureId(), "u_Texture", 0);
                    setFeatureFlag("u_Textured", true);
                }

                if (obj.getMaterial().getNormalTextureId() != -1) {
                    setTexture(obj.getMaterial().getNormalTextureId(), "u_NormalTexture", 1);
                    setFeatureFlag("u_NormalTextured", true);
                }

                if (obj.getMaterial().getEmissiveTextureId() != -1) {
                    setTexture(obj.getMaterial().getEmissiveTextureId(), "u_EmissiveTexture", 2);
                    setFeatureFlag("u_EmissiveTextured", true);
                }
            }
        }

        // pass in the SkyBox texture
        if (textureId != -1 && supportsTextureCube()){
            setTextureCube(textureId);
        }

        // pass in light position for lighting
        if (supportsLighting()) {
            boolean toggle = lightingEnabled && obj.getNormalsBuffer() != null;
            setFeatureFlag("u_Lighted", toggle);
            setUniform3(lightPosInWorldSpace,"u_LightPos");
            setUniform3(cameraPos,"u_cameraPos");
        }

        // pass in joint transformation for animated model
        int in_weightsHandle = -1;
        int in_jointIndicesHandle = -1;
        if (supportsJoints()){
            final boolean animationOK = obj instanceof AnimatedModel
                    && ((AnimatedModel) obj).getAnimation() != null
                    && (((AnimatedModel) obj).getAnimation()).isInitialized();
            boolean toggle = this.animationEnabled && animationOK;
            if(toggle) {
                in_weightsHandle = setVBO("in_weights", ((AnimatedModel) obj).getVertexWeights(), COORDS_PER_VERTEX);
                in_jointIndicesHandle = setVBO("in_jointIndices", ((AnimatedModel) obj).getJointIds(), COORDS_PER_VERTEX);
                setUniformMatrix4(((AnimatedModel) obj).getBindShapeMatrix(), "u_BindShapeMatrix");
                setJointTransforms((AnimatedModel) obj);
            }
            setFeatureFlag("u_Animated", toggle);
        }

        // draw mesh
        drawShape(obj, drawMode, drawSize);

        // Disable vertex handlers
        disableVBO(mPositionHandle);
        disableVBO(mColorHandle);
        disableVBO(mNormalHandle);
        disableVBO(mTextureHandle);
        disableVBO(in_weightsHandle);
        disableVBO(in_jointIndicesHandle);
    }

    private int setVBO(final String shaderVariableName, final FloatBuffer vertexBufferObject, int coordsPerVertex) {
        int handler = GLES20.glGetAttribLocation(mProgram, shaderVariableName);
        GLUtil.checkGlError("glGetAttribLocation");

        GLES20.glEnableVertexAttribArray(handler);
        GLUtil.checkGlError("glEnableVertexAttribArray");

        // Pass in the normal information
        vertexBufferObject.position(0);
        GLES20.glVertexAttribPointer(handler, coordsPerVertex, GLES20.GL_FLOAT, false, 0, vertexBufferObject);
        GLUtil.checkGlError("glVertexAttribPointer");

        return handler;
    }

    private void setUniform3(float[] uniform3f, String variableName) {
        int handle = GLES20.glGetUniformLocation(mProgram, variableName);
        GLUtil.checkGlError("glGetUniformLocation");

        GLES20.glUniform3fv(handle, 1, uniform3f, 0);
        GLUtil.checkGlError("glUniform3fv");
    }

    private void setUniform4(float[] uniform4f, String variableName) {
        int handle = GLES20.glGetUniformLocation(mProgram, variableName);
        GLUtil.checkGlError("glGetUniformLocation");

        GLES20.glUniform4fv(handle, 1, uniform4f, 0);
        GLUtil.checkGlError("glUniform4fv");
    }

    private void setUniformMatrix4(float[] matrix, String variableName) {
        int handle = GLES20.glGetUniformLocation(mProgram, variableName);
        GLUtil.checkGlError("glGetUniformLocation");

        // Pass in the light position in eye space.
        GLES20.glUniformMatrix4fv(handle, 1, false, matrix, 0);
        GLUtil.checkGlError("glUniformMatrix4fv");
    }

    private void disableVBO(int handle) {
        if (handle != -1) {
            GLES20.glDisableVertexAttribArray(handle);
            GLUtil.checkGlError("glDisableVertexAttribArray");
        }
    }

    private boolean supportsMMatrix(){
        return features.contains("u_MMatrix");
    }

    private boolean supportsTextureCube(){
        return features.contains("u_TextureCube");
    }

    private boolean supportsColors() {
        return features.contains("a_Color");
    }

    private boolean supportsNormals() {
        return features.contains("a_Normal");
    }

    private boolean supportsLighting() {
        return features.contains("u_LightPos");
    }

    private boolean supportsTextures() {
        return features.contains("a_TexCoordinate");
    }

    private void setFeatureFlag(String variableName, boolean enabled) {
        int handle = GLES20.glGetUniformLocation(mProgram, variableName);
        GLUtil.checkGlError("glGetUniformLocation");

        GLES20.glUniform1i(handle, enabled ? 1 : 0);
        GLUtil.checkGlError("glUniform1i");
    }

    private void setTexture(int textureId, String variableName, int textureIndex) {
        int mTextureUniformHandle = GLES20.glGetUniformLocation(mProgram, variableName);
        GLUtil.checkGlError("glGetUniformLocation");

        // Set the active texture unit to texture unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + textureIndex);
        GLUtil.checkGlError("glActiveTexture");

        // Bind to the texture in OpenGL
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLUtil.checkGlError("glBindTexture");

        // Tell the texture uniform sampler to use this texture in the shader by binding to texture unit 0.
        GLES20.glUniform1i(mTextureUniformHandle, textureIndex);
        GLUtil.checkGlError("glUniform1i");
    }

    private void setTextureCube(int textureId) {

        int mTextureUniformHandle = GLES20.glGetUniformLocation(mProgram, "u_TextureCube");
        GLUtil.checkGlError("glGetUniformLocation");

        // Set the active texture unit to texture unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLUtil.checkGlError("glActiveTexture");

        // Bind to the texture in OpenGL
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, textureId);
        GLUtil.checkGlError("glBindTexture");

        // Tell the texture uniform sampler to use this texture in the shader by binding to texture unit 0.
        GLES20.glUniform1i(mTextureUniformHandle, 0);
        GLUtil.checkGlError("glUniform1i");

    }

    private boolean supportsJoints() {
        return features.contains("in_jointIndices") && features.contains("in_weights");
    }

    private void setJointTransforms(AnimatedModel animatedModel) {
        float[][] jointTransformsArray = animatedModel.getJointTransforms();

        // TODO: optimize this (memory allocation)
        for (int i = 0; i < jointTransformsArray.length; i++) {
            float[] jointTransform = jointTransformsArray[i];
            String jointTransformHandleName = cache1.get(i);
            if (jointTransformHandleName == null) {
                jointTransformHandleName = "jointTransforms[" + i + "]";
                cache1.put(i, jointTransformHandleName);
            }
            setUniformMatrix4(jointTransform, jointTransformHandleName);
        }
    }

    private void drawShape(Object3DData obj, int drawMode, int drawSize) {


        int drawBufferType = -1;
        final Buffer drawOrderBuffer;
        final FloatBuffer vertexBuffer;
        if (obj.isDrawUsingArrays()) {
            drawOrderBuffer = null;
            vertexBuffer = obj.getVertexBuffer();
        } else {
            vertexBuffer = obj.getVertexBuffer();
            if (drawUsingUnsignedInt) {
                drawOrderBuffer = obj.getDrawOrder();
                drawBufferType = GLES20.GL_UNSIGNED_INT;
            } else {
                drawOrderBuffer = obj.getDrawOrderAsShort();
                drawBufferType = GLES20.GL_UNSIGNED_SHORT;
            }
        }
        vertexBuffer.position(0);

        List<int[]> drawModeList = obj.getDrawModeList();
        if (drawModeList != null) {
            if (obj.isDrawUsingArrays()) {
                drawPolygonsUsingArrays(drawMode, drawModeList);
            } else {
                drawPolygonsUsingIndex(drawOrderBuffer, drawBufferType, drawModeList);
            }
        } else {
            if (obj.isDrawUsingArrays()) {
                drawTrianglesUsingArrays(drawMode, drawSize, vertexBuffer.capacity() / COORDS_PER_VERTEX);
            } else {
                drawTrianglesUsingIndex(obj, drawMode, drawSize, drawOrderBuffer, drawBufferType);
            }
        }
    }

    private void drawTrianglesUsingArrays(int drawMode, int drawSize, int drawCount) {
        if (drawSize <= 0) {
            // if we want to animate, initialize counter=0 at variable declaration
            if (this.shift >= 0) {
                double rotation = ((SystemClock.uptimeMillis() % 10000) / 10000f) * (Math.PI * 2);

                if (this.shift == 0d) {
                    this.shift = rotation;
                }
                drawCount = (int) ((Math.sin(rotation - this.shift + Math.PI / 2 * 3) + 1) / 2f * drawCount);
            }
            GLES20.glDrawArrays(drawMode, 0, drawCount);
            GLUtil.checkGlError("glDrawArrays");
        } else {
            //Log.d(obj.getId(),"Drawing single triangles using arrays...");
            for (int i = 0; i < drawCount; i += drawSize) {
                GLES20.glDrawArrays(drawMode, i, drawSize);
                GLUtil.checkGlError("glDrawArrays");
            }
        }
    }

    private void drawTrianglesUsingIndex(Object3DData obj, int drawMode, int drawSize, Buffer drawOrderBuffer, int drawBufferType) {
        if (drawSize <= 0) {
            // String mode = drawMode == GLES20.GL_POINTS ? "Points" : drawMode == GLES20.GL_LINES? "Lines": "Triangles?";
            // Log.v(obj.getId(),"Drawing all elements with mode '"+drawMode+"'...");


            /*for (int i = 0; i<obj.getOldElements().size(); i++) {
                drawOrderBuffer = obj.getOldElements().get(i);
                drawOrderBuffer.position(0);
                GLES20.glDrawElements(drawMode, drawOrderBuffer.capacity(), drawBufferType,
                        drawOrderBuffer);
                boolean error = GLUtil.checkGlError("glDrawElements");
                if (drawUsingUnsignedInt && error) {
                    drawUsingUnsignedInt = false;
                }
            }*/

            int size = obj.getElements().size();
            /*if (id != flags.get(obj.getElements())) {
                Log.i("GLES20Renderer", "Rendering elements... obj: " + obj.getId()
                        + ", total:" + size);
                flags.put(obj.getElements(), this.id);
            }*/

            // draw rest
            for (int i = 0; i < size; i++) {

                // get next element
                Element element = obj.getElements().get(i);

                if (element.getMaterial() == null) {
                    drawObjectElement(obj, drawMode, drawBufferType, size, i, element);
                }
            }

            // draw opaque elements
            for (int i = 0; i < size; i++) {

                // get next element
                Element element = obj.getElements().get(i);
                if (element.getMaterial() != null && element.getMaterial().getAlpha() == 1.0f) {
                    drawObjectElement(obj, drawMode, drawBufferType, size, i, element);
                }
            }

            // draw translucent elements
            for (int i = 0; i < size; i++) {

                // get next element
                Element element = obj.getElements().get(i);

                if (element.getMaterial() != null && element.getMaterial().getAlpha() < 1.0f) {
                    drawObjectElement(obj, drawMode, drawBufferType, size, i, element);
                }
            }



        } else {
            //Log.d(obj.getId(),"Drawing single elements of size '"+drawSize+"'...");
            for (int i = 0; i < drawOrderBuffer.capacity(); i += drawSize) {
                drawOrderBuffer.position(i);
                GLES20.glDrawElements(drawMode, drawSize, drawBufferType, drawOrderBuffer);
                boolean error = GLUtil.checkGlError("glDrawElements");
                if (drawUsingUnsignedInt && error) {
                    drawUsingUnsignedInt = false;
                }
            }
        }
    }

    private void drawObjectElement(Object3DData obj, int drawMode, int drawBufferType, int size, int i, Element element) {
        Buffer drawOrderBuffer = element.getIndexBuffer();

        // log event
        /*if (id != flags.get(element)) {
            Log.v("GLES20Renderer", "Rendering element " + i + "....  " + element);
            flags.put(element, id);
        }*/

        if (element.getMaterial() != null && element.getMaterial().getColor() != null){
            setUniform4(element.getMaterial().getColor(), "vColor");
        } else {
            setUniform4(DEFAULT_COLOR, "vColor");
        }

        // default is no textured
        if (supportsColors()){
            setFeatureFlag("u_Coloured", obj.getColorsBuffer() != null);
        }

        // default is no textured
        if (supportsTextures()){
            setFeatureFlag("u_Textured", obj.getTextureBuffer() != null
                    && element.getMaterial() != null && element.getMaterial().getTextureId() != -1
                    && texturesEnabled);
        }

        if (element.getMaterial() != null) {

            // load color map
            if (element.getMaterial().getTextureId() == -1 &&
                    element.getMaterial().getColorTexture() != null){

                // log event
                Log.i("ModelRenderer", "Binding texture map... " + element.getMaterial().getName());

                // bind bitmap
                int handler = GLUtil.loadTexture(element.getMaterial().getColorTexture());

                element.getMaterial().setTextureId(handler);
            }

            // load normal map
            if (element.getMaterial().getNormalTextureId() == -1 &&
                    element.getMaterial().getNormalTexture() != null){

                // log event
                Log.i("ModelRenderer", "Binding normal map... " + element.getMaterial().getName());

                // bind bitmap
                int handler = GLUtil.loadTexture(element.getMaterial().getNormalTexture());

                element.getMaterial().setNormalTextureId(handler);
            }

            // load emissive map
            if (element.getMaterial().getEmissiveTextureId() == -1 &&
                    element.getMaterial().getEmissiveTexture() != null){

                // log event
                Log.i("ModelRenderer", "Binding emmissive map... " + element.getMaterial().getName());

                // bind bitmap
                int handler = GLUtil.loadTexture(element.getMaterial().getEmissiveTexture());

                element.getMaterial().setEmissiveTextureId(handler);
            }

            // load color map
            if (element.getMaterial().getTextureId() == -1 &&
                    element.getMaterial().getTextureData() != null){

                // log event
                Log.i("ModelRenderer", "Binding texture data... " + element.getMaterial().getName());

                // bind bitmap
                int handler = GLUtil.loadTexture(element.getMaterial().getTextureData());

                element.getMaterial().setTextureId(handler);
            }
            if (element.getMaterial().getTextureId() != -1 && supportsTextures()) {
                setTexture(element.getMaterial().getTextureId(), "u_Texture", 0);
                setFeatureFlag("u_Textured",texturesEnabled);
            }

            if (element.getMaterial().getNormalTextureId() != -1 && supportsTextures()) {
                setTexture(element.getMaterial().getNormalTextureId(), "u_NormalTexture", 1);
                setFeatureFlag("u_NormalTextured",texturesEnabled);
            }

            if (element.getMaterial().getEmissiveTextureId() != -1 && supportsTextures()){
                setTexture(element.getMaterial().getEmissiveTextureId(), "u_EmissiveTexture", 2);
                setFeatureFlag("u_EmissiveTextured", texturesEnabled);
            }
        }

        if(drawUsingUnsignedInt == false){
            ShortBuffer indexShortBuffer = null;
            drawOrderBuffer.position(0);
            if (indexShortBuffer == null && drawOrderBuffer != null) {
                indexShortBuffer = IOUtils.createShortBuffer(((IntBuffer) drawOrderBuffer).capacity());
                for (int j = 0; j < indexShortBuffer.capacity(); j++) {
                    indexShortBuffer.put((short) ((IntBuffer) drawOrderBuffer).get(j));
                }
                drawOrderBuffer = indexShortBuffer;
            }
        }

        // draw element
        drawOrderBuffer.position(0);
        GLES20.glDrawElements(drawMode, drawOrderBuffer.capacity(), drawBufferType,
                drawOrderBuffer);
        boolean error = GLUtil.checkGlError("glDrawElements");
        if (drawUsingUnsignedInt && error) {
            drawUsingUnsignedInt = false;
        }

        // log event
        /*if (id != flags.get(element)) {
            Log.i("GLES20Renderer", "Rendering element " + i + " finished");
            flags.put(element, this.id);
        }*/
    }

    private void drawPolygonsUsingIndex(Buffer drawOrderBuffer, int drawBufferType, List<int[]> polygonsList) {
        // Log.d(obj.getId(),"Drawing single polygons using elements...");
        for (int i = 0; i < polygonsList.size(); i++) {
            int[] drawPart = polygonsList.get(i);
            int drawModePolygon = drawPart[0];
            int vertexPos = drawPart[1];
            int drawSizePolygon = drawPart[2];
            drawOrderBuffer.position(vertexPos);
            GLES20.glDrawElements(drawModePolygon, drawSizePolygon, drawBufferType, drawOrderBuffer);
            boolean error = GLUtil.checkGlError("glDrawElements");
            if (drawUsingUnsignedInt && error) {
                drawUsingUnsignedInt = false;
            }
        }
    }

    private void drawPolygonsUsingArrays(int drawMode, List<int[]> polygonsList) {
        // Log.v(obj.getId(), "Drawing single polygons using arrays...");
        for (int j = 0; j < polygonsList.size(); j++) {
            int[] polygon = polygonsList.get(j);
            int drawModePolygon = polygon[0];
            int vertexPos = polygon[1];
            int drawSizePolygon = polygon[2];
            if (drawMode == GLES20.GL_LINE_LOOP && polygon[2] > 3) {
                // is this wireframe?
                // Log.v("GLES20Renderer","Drawing wireframe for '" + obj.getId() + "' (" + drawSizePolygon + ")...");
                for (int i = 0; i < polygon[2] - 2; i++) {
                    // Log.v("GLES20Renderer","Drawing wireframe triangle '" + i + "' for '" + obj.getId() + "'...");
                    GLES20.glDrawArrays(drawMode, polygon[1] + i, 3);
                    GLUtil.checkGlError("glDrawArrays");
                }
            } else {
                GLES20.glDrawArrays(drawMode, polygon[1], polygon[2]);
                GLUtil.checkGlError("glDrawArrays");
            }
        }
    }

    @Override
    public String toString() {
        return "GLES20Renderer{" +
                "id='" + id + '\'' +
                ", features=" + features +
                '}';
    }
}