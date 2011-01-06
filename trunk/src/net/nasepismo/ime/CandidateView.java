/*
 * Copyright (C) 2008-2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.nasepismo.ime;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.google.inject.internal.Lists;
import com.google.inject.internal.Nullable;

/**
 * Manages the display of the typed-in suggestions.
 *
 * @author filmil@gmail.com (Filip Miletic)
 */
public class CandidateView extends View {

  private static final int OUT_OF_BOUNDS = -1;

  private SoftKeyboard softKeyboard;
  private List<String> suggestionList;
  private int selectedIndex;
  private int touchX = OUT_OF_BOUNDS;
  private Drawable selectionHighlight;
  private boolean isWordValid;

  private Rect padding;

  private static final int MAX_SUGGESTIONS = 32;
  private static final int SCROLL_PIXELS = 20;

  private int[] wordWidths = new int[MAX_SUGGESTIONS];
  private int[] wordX = new int[MAX_SUGGESTIONS];

  private static final int X_GAP = 10;

  private static final List<String> EMPTY_LIST = Lists.newArrayList();

  private int normalColor;
  private int recommendedColor;
  private int otherColor;
  private int verticalPadding;
  private Paint paint;
  private boolean isScrolled;
  private int targetScrollX;

  private int totalWidth;

  private GestureDetector gestureDetector;

  /**
   * Construct a CandidateView for showing suggested words for completion.
   */
  public CandidateView(Context context) {
    super(context);
    selectionHighlight = context.getResources().getDrawable(
        android.R.drawable.list_selector_background);
    selectionHighlight.setState(new int[] {
        android.R.attr.state_enabled,
        android.R.attr.state_focused,
        android.R.attr.state_window_focused,
        android.R.attr.state_pressed
    });

    Resources r = context.getResources();

    setBackgroundColor(r.getColor(R.color.candidate_background));

    normalColor = r.getColor(R.color.candidate_normal);
    recommendedColor = r.getColor(R.color.candidate_recommended);
    otherColor = r.getColor(R.color.candidate_other);
    verticalPadding = r.getDimensionPixelSize(R.dimen.candidate_vertical_padding);

    paint = new Paint();
    paint.setColor(normalColor);
    paint.setAntiAlias(true);
    paint.setTextSize(r.getDimensionPixelSize(R.dimen.candidate_font_height));
    paint.setStrokeWidth(0);

    gestureDetector = new GestureDetector(new GestureDetector.SimpleOnGestureListener() {
      @Override
      public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        isScrolled = true;
        int sx = getScrollX();
        sx += distanceX;
        if (sx < 0) {
          sx = 0;
        }
        if (sx + getWidth() > totalWidth) {
          sx -= distanceX;
        }
        targetScrollX = sx;
        scrollTo(sx, getScrollY());
        invalidate();
        return true;
      }
    });
    setHorizontalFadingEdgeEnabled(true);
    setWillNotDraw(false);
    setHorizontalScrollBarEnabled(false);
    setVerticalScrollBarEnabled(false);
  }

  /**
   * A connection back to the service to communicate with the text field
   * @param listener
   */
  public void setService(SoftKeyboard listener) {
    softKeyboard = listener;
  }

  @Override
  public int computeHorizontalScrollRange() {
    return totalWidth;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int measuredWidth = resolveSize(50, widthMeasureSpec);

    // Get the desired height of the icon menu view (last row of items does
    // not have a divider below)
    Rect padding = new Rect();
    selectionHighlight.getPadding(padding);
    final int desiredHeight =
        ((int)paint.getTextSize()) + verticalPadding + padding.top + padding.bottom;

    // Maximum possible width and desired height
    setMeasuredDimension(measuredWidth,
        resolveSize(desiredHeight, heightMeasureSpec));
  }

  /**
   * If the canvas is null, then only touch calculations are performed to pick the target
   * candidate.
   */
  @Override
  protected void onDraw(@Nullable Canvas canvas) {
    if (canvas != null) {
      super.onDraw(canvas);
    }
    totalWidth = 0;
    if (suggestionList == null) return;

    if (padding == null) {
      padding = new Rect(0, 0, 0, 0);
      if (getBackground() != null) {
        getBackground().getPadding(padding);
      }
    }
    int x = 0;
    final int count = suggestionList.size();
    final int height = getHeight();
    final Rect paddingConst = padding;
    final Paint paintConst = paint;
    final int touchXConst = touchX;
    final int scrollX = getScrollX();
    final boolean scrolled = isScrolled;
    final boolean typedWordValid = isWordValid;
    final int y = (int) (((height - paint.getTextSize()) / 2) - paint.ascent());

    for (int i = 0; i < count; i++) {
      String suggestion = suggestionList.get(i);
      float textWidth = paintConst.measureText(suggestion);
      final int wordWidth = (int) textWidth + X_GAP * 2;

      wordX[i] = x;
      wordWidths[i] = wordWidth;
      paintConst.setColor(normalColor);
      if (touchXConst + scrollX >= x && touchXConst + scrollX < x + wordWidth && !scrolled) {
        if (canvas != null) {
          canvas.translate(x, 0);
          selectionHighlight.setBounds(0, paddingConst.top, wordWidth, height);
          selectionHighlight.draw(canvas);
          canvas.translate(-x, 0);
        }
        selectedIndex = i;
      }

      if (canvas != null) {
        if ((i == 1 && !typedWordValid) || (i == 0 && typedWordValid)) {
          paintConst.setFakeBoldText(true);
          paintConst.setColor(recommendedColor);
        } else if (i != 0) {
          paintConst.setColor(otherColor);
        }
        canvas.drawText(suggestion, x + X_GAP, y, paintConst);
        paintConst.setColor(otherColor);
        canvas.drawLine(
            x + wordWidth + 0.5f, paddingConst.top, x + wordWidth + 0.5f, height + 1, paintConst);
        paintConst.setFakeBoldText(false);
      }
      x += wordWidth;
    }
    totalWidth = x;
    if (targetScrollX != getScrollX()) {
      scrollToTarget();
    }
  }

  private void scrollToTarget() {
    int sx = getScrollX();
    if (targetScrollX > sx) {
      sx += SCROLL_PIXELS;
      if (sx >= targetScrollX) {
        sx = targetScrollX;
        requestLayout();
      }
    } else {
      sx -= SCROLL_PIXELS;
      if (sx <= targetScrollX) {
        sx = targetScrollX;
        requestLayout();
      }
    }
    scrollTo(sx, getScrollY());
    invalidate();
  }

  public void setSuggestions(List<String> suggestions, boolean completions,
      boolean typedWordValid) {
    clear();
    if (suggestions != null) {
      suggestionList = new ArrayList<String>(suggestions);
    }
    isWordValid = typedWordValid;
    scrollTo(0, 0);
    targetScrollX = 0;
    // Compute the total width
    onDraw(null);
    invalidate();
    requestLayout();
  }

  public void clear() {
    suggestionList = EMPTY_LIST;
    touchX = OUT_OF_BOUNDS;
    selectedIndex = -1;
    invalidate();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {

    if (gestureDetector.onTouchEvent(event)) {
      return true;
    }

    int action = event.getAction();
    int x = (int) event.getX();
    int y = (int) event.getY();
    touchX = x;

    switch (action) {
    case MotionEvent.ACTION_DOWN:
      isScrolled = false;
      invalidate();
      break;
    case MotionEvent.ACTION_MOVE:
      if (y <= 0) {
        // Fling up!?
            if (selectedIndex >= 0) {
              softKeyboard.pickSuggestionManually(selectedIndex);
              selectedIndex = -1;
            }
      }
      invalidate();
      break;
    case MotionEvent.ACTION_UP:
      if (!isScrolled) {
        if (selectedIndex >= 0) {
          softKeyboard.pickSuggestionManually(selectedIndex);
        }
      }
      selectedIndex = -1;
      removeHighlight();
      requestLayout();
      break;
    }
    return true;
  }

  /**
   * For flick through from keyboard, call this method with the x coordinate of the flick
   * gesture.
   */
  public void takeSuggestionAt(float x) {
    touchX = (int) x;
    // To detect candidate
    onDraw(null);
    if (selectedIndex >= 0) {
      softKeyboard.pickSuggestionManually(selectedIndex);
    }
    invalidate();
  }

  private void removeHighlight() {
    touchX = OUT_OF_BOUNDS;
    invalidate();
  }
}
