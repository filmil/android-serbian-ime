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

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.inputmethodservice.Keyboard;
import android.view.inputmethod.EditorInfo;

/**
 * The Serbian keyboard.
 *
 * @author filmil@gmail.com (Filip Miletic)
 */
public class SerbianKeyboard extends Keyboard {

  private Keyboard.Key enterKey;

  public SerbianKeyboard(Context context, int layoutResId) {
    super(context, layoutResId);
  }

  public SerbianKeyboard(
      Context context, int layoutTemplateResId, CharSequence characters, int columns,
          int horizontalPadding) {
    super(context, layoutTemplateResId, characters, columns, horizontalPadding);
  }

  @Override
  protected Keyboard.Key createKeyFromXml(
      Resources res, Row parent, int x, int y, XmlResourceParser parser) {
    Keyboard.Key key = new Key(res, parent, x, y, parser);
    if (key.codes[0] == 10) {
      enterKey = key;
    }
    return key;
  }

  /**
   * This looks at the ime options given by the current editor, to set the
   * appropriate label on the keyboard's enter key (if it has one).
   */
  void setImeOptions(Resources res, int options) {
    if (enterKey == null) {
      return;
    }

    switch (options & (EditorInfo.IME_MASK_ACTION | EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
      case EditorInfo.IME_ACTION_GO:
        enterKey.iconPreview = null;
        enterKey.icon = null;
        enterKey.label = res.getText(R.string.label_go_key);
        break;
      case EditorInfo.IME_ACTION_NEXT:
        enterKey.iconPreview = null;
        enterKey.icon = null;
        enterKey.label = res.getText(R.string.label_next_key);
        break;
      case EditorInfo.IME_ACTION_SEARCH:
        enterKey.icon = res.getDrawable(R.drawable.sym_keyboard_search);
        enterKey.label = null;
        break;
      case EditorInfo.IME_ACTION_SEND:
        enterKey.iconPreview = null;
        enterKey.icon = null;
        enterKey.label = res.getText(R.string.label_send_key);
        break;
      default:
        enterKey.icon = res.getDrawable(
            R.drawable.sym_keyboard_return);
        enterKey.label = null;
        break;
      }
  }

  /** Cancel key has modified 'inside' behavior. */
  static class Key extends Keyboard.Key {

    public Key(Resources res, Keyboard.Row parent, int x, int y, XmlResourceParser parser) {
      super(res, parent, x, y, parser);
    }

    /**
     * Overriding this method so that we can reduce the target area for the key that
     * closes the keyboard.
     */
    @Override
    public boolean isInside(int x, int y) {
      return super.isInside(x, codes[0] == KEYCODE_CANCEL ? y - 10 : y);
    }
  }
}
