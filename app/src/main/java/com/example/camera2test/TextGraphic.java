package com.example.camera2test;
// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.example.camera2test.GraphicOverlay.Graphic;


/**
 * Graphic instance for rendering TextBlock position, size, and ID within an associated graphic
 * overlay view.
 */
public class TextGraphic extends Graphic {

    private static final int TEXT_COLOR = Color.WHITE; //Todo:What if i can use a tts engine which will change the colour of the text the text
    private static final float TEXT_SIZE = 25.0f; // TODO i may have to make a method which will automatically adjust text size acoording to real size
    private static final float STROKE_WIDTH = 2.0f;

    private final Paint rectPaint;
    private final Paint textPaint;
    private final boolean isElementUsed;
    private FirebaseVisionText.Line text;
    private FirebaseVisionText.Element elementText;


    TextGraphic(GraphicOverlay overlay, FirebaseVisionText.Line text) {
        super(overlay);
        isElementUsed = false;
        this.text = text; //Todo: to make a map which will store each word and use it

        rectPaint = new Paint();
        rectPaint.setColor(Color.BLACK);
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(STROKE_WIDTH);

        textPaint = new Paint();
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(TEXT_SIZE);
        postInvalidate();
    }

    TextGraphic(GraphicOverlay overlay, FirebaseVisionText.Element elementText) {
        super(overlay);
        isElementUsed = true;
        overlay.isElementUsed = true;
        this.elementText = elementText;

        rectPaint = new Paint();
        rectPaint.setColor(TEXT_COLOR);
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(STROKE_WIDTH);

        textPaint = new Paint();
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(TEXT_SIZE);
        postInvalidate();
    }


    public FirebaseVisionText.Line getTextBlock() {
        return text;
    }

    public FirebaseVisionText.Element getElementText() {
        return elementText;
    }


    /**
     * Draws the text block annotations for position, size, and raw value on the supplied canvas.
     */
    @Override
    public void draw(Canvas canvas) {
        if (text == null && !isElementUsed) {
            throw new IllegalStateException("Attempting to draw a null text.");
        } else if (elementText == null && isElementUsed) {
            throw new IllegalStateException("Attempting to draw a null text.");
        }

        /**Why do we have to translate the coordinates of the bounding box?
         *   Because the bounding coordinates are relative to the frame that was detected on, not the one that we're viewing.
         * If you zoom in using pinch-to-zoom, for instance, they won't line up.
         */

        // Draws the bounding box around the TextBlock.


        /**Here we are using the boolean to determine which constructor had been used*/
        if (!isElementUsed) {
            RectF rect = new RectF(text.getBoundingBox());
            rect = translateRect(rect);
            canvas.drawRect(rect, rectPaint);
            canvas.drawText(text.getText(), rect.left, rect.bottom, textPaint); // rect.left and rect.bottom are the coordinates of the text they can be used for mapping puposes

        }
        if (isElementUsed) {
            RectF rect = new RectF(elementText.getBoundingBox());
            rect = translateRect(rect);
            canvas.drawRect(rect, rectPaint);
            canvas.drawText(elementText.getText(), rect.left, rect.bottom, textPaint); // rect.left and rect.bottom are the coordinates of the text they can be used for mapping puposes

        }



        /**Here we are defining a Map which takes a string(Text) and a Integer X_Axis.The text will act as key to the X_Axis.
         Once We Got the X_Axis we will pass its value to a SparseIntArray which will Assign X Axis To Y Axis
         .Then We might Create another Map which will Store Both The text and the coordinates*/
        //  int X_Axis = (int) rect.left;
        //  int Y_Axis = (int) rect.bottom;

        //
        // Log.d("PositionXY", "x: " + X_Axis + " |Y: " + Y_Axis);
    }


    @Override
    public boolean contains(float x, float y) {

        if (text == null && !isElementUsed) {
            return false;
        } else if (elementText == null && isElementUsed) {
            return false;
        }

        RectF rect;
        /**Here we are again using the boolean to determine which constructor had been used*/
        if (!isElementUsed) {
            rect = new RectF(text.getBoundingBox());
        } else {
            rect = new RectF(elementText.getBoundingBox());
        }
        rect = translateRect(rect);
        return rect.contains(x, y);

        //TODO: What i need to do is to create a array which will store text.As we can access get the text by touching at a specfic point on the screen.We will use its index as a base value
        //For a loop which will automatically increase its value and TTS engine will speak based on the index.
        //We can then create a seprate map which will store locations.
    }
}