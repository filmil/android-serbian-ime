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

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.text.method.MetaKeyKeyListener;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * Example of writing an input method for a soft keyboard.  This code is
 * focused on simplicity over completeness, so it should in no way be considered
 * to be a complete soft keyboard implementation.  Its purpose is to provide
 * a basic example for how you would get started writing an input method, to
 * be fleshed out as appropriate.
 */
public class SoftKeyboard extends InputMethodService
    implements KeyboardView.OnKeyboardActionListener {
  static final boolean DEBUG = false;

  /**
   * This boolean indicates the optional example code for performing
   * processing of hard keys in addition to regular text generation
   * from on-screen interaction.  It would be used for input methods that
   * perform language translations (such as converting text entered on
   * a QWERTY keyboard to Chinese), but may not be used for input methods
   * that are primarily intended to be used for on-screen text entry.
   */
  static final boolean PROCESS_HARD_KEYS = true;

  private KeyboardView inputView;
  private CandidateView candidateView;
  private CompletionInfo[] mCompletions;

  private StringBuilder composing = new StringBuilder();
  private boolean enablePrediction;
  private boolean enableCompletion;
  private int lastDisplayWidth;
  private boolean isCapsLockPressed;
  private long lastShiftTime;
  private long metaState;

  private SerbianKeyboard symbolsKeyboard;
  private SerbianKeyboard symbolsShiftedKeyboard;
  private SerbianKeyboard baseKeyboard;

  private SerbianKeyboard currentKeyboard;

  private String wordSeparators;

  /**
   * Main initialization of the input method component.  Be sure to call
   * to super class.
   */
  @Override
  public void onCreate() {
    super.onCreate();
    wordSeparators = getResources().getString(R.string.word_separators);
  }

  /**
   * This is the point where you can do all of your UI initialization.  It
   * is called after creation and any configuration change.
   */
  @Override
  public void onInitializeInterface() {
    if (baseKeyboard != null) {
      // Configuration changes can happen after the keyboard gets recreated,
      // so we need to be able to re-build the keyboards if the available
      // space has changed.
      int displayWidth = getMaxWidth();
      if (displayWidth == lastDisplayWidth) return;
      lastDisplayWidth = displayWidth;
    }
    baseKeyboard = new SerbianKeyboard(this, R.xml.qwerty);
    symbolsKeyboard = new SerbianKeyboard(this, R.xml.symbols);
    symbolsShiftedKeyboard = new SerbianKeyboard(this, R.xml.symbols_shift);
  }

  /**
   * Called by the framework when your view for creating input needs to
   * be generated.  This will be called the first time your input method
   * is displayed, and every time it needs to be re-created such as due to
   * a configuration change.
   */
  @Override
  public View onCreateInputView() {
    inputView = (KeyboardView) getLayoutInflater().inflate(
        R.layout.input, null);
    inputView.setOnKeyboardActionListener(this);
    inputView.setKeyboard(baseKeyboard);
    return inputView;
  }

  /**
   * Called by the framework when your view for showing candidates needs to
   * be generated, like {@link #onCreateInputView}.
   */
  @Override
  public View onCreateCandidatesView() {
    candidateView = new CandidateView(this);
    candidateView.setService(this);
    return candidateView;
  }

  /**
   * This is the main point where we do our initialization of the input method
   * to begin operating on an application.  At this point we have been
   * bound to the client, and are now receiving all of the detailed information
   * about the target of our edits.
   */
  @Override
  public void onStartInput(EditorInfo attribute, boolean restarting) {
    super.onStartInput(attribute, restarting);

    // Reset our state.  We want to do this even if restarting, because
    // the underlying state of the text editor could have changed in any way.
    composing.setLength(0);
    updateCandidates();

    if (!restarting) {
      // Clear shift states.
      metaState = 0;
    }

    enablePrediction = false;
    enableCompletion = false;
    mCompletions = null;

    // We are now going to initialize our state based on the type of
    // text being edited.
    switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
    case EditorInfo.TYPE_CLASS_NUMBER:
    case EditorInfo.TYPE_CLASS_DATETIME:
      // Numbers and dates default to the symbols keyboard, with
      // no extra features.
      currentKeyboard = symbolsKeyboard;
      break;

    case EditorInfo.TYPE_CLASS_PHONE:
      // Phones will also default to the symbols keyboard, though
      // often you will want to have a dedicated phone keyboard.
      currentKeyboard = symbolsKeyboard;
      break;

    case EditorInfo.TYPE_CLASS_TEXT:
      // This is general text editing.  We will default to the
      // normal alphabetic keyboard, and assume that we should
      // be doing predictive text (showing candidates as the
      // user types).
      currentKeyboard = baseKeyboard;
      enablePrediction = true;

      // We now look for a few special variations of text that will
      // modify our behavior.
      int variation = attribute.inputType &  EditorInfo.TYPE_MASK_VARIATION;
      if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
          variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
        // Do not display predictions / what the user is typing
        // when they are entering a password.
        enablePrediction = false;
      }

      if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
          || variation == EditorInfo.TYPE_TEXT_VARIATION_URI
          || variation == EditorInfo.TYPE_TEXT_VARIATION_FILTER) {
        // Our predictions are not useful for e-mail addresses
        // or URIs.
        enablePrediction = false;
      }

      if ((attribute.inputType&EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
        // If this is an auto-complete text view, then our predictions
        // will not be shown and instead we will allow the editor
        // to supply their own.  We only show the editor's
        // candidates when in fullscreen mode, otherwise relying
        // own it displaying its own UI.
        enablePrediction = false;
        enableCompletion = isFullscreenMode();
      }

      // We also want to look at the current state of the editor
      // to decide whether our alphabetic keyboard should start out
      // shifted.
      updateShiftKeyState(attribute);
      break;

    default:
      // For all unknown input types, default to the alphabetic
      // keyboard with no special features.
      currentKeyboard = baseKeyboard;
      updateShiftKeyState(attribute);
    }

    // Update the label on the enter key, depending on what the application
    // says it will do.
    currentKeyboard.setImeOptions(getResources(), attribute.imeOptions);
  }

  /**
   * This is called when the user is done editing a field.  We can use
   * this to reset our state.
   */
  @Override
  public void onFinishInput() {
    super.onFinishInput();

    // Clear current composing text and candidates.
    composing.setLength(0);
    updateCandidates();

    // We only hide the candidates window when finishing input on
    // a particular editor, to avoid popping the underlying application
    // up and down if the user is entering text into the bottom of
    // its window.
    setCandidatesViewShown(false);

    currentKeyboard = baseKeyboard;
    if (inputView != null) {
      inputView.closing();
    }
  }

  @Override
  public void onStartInputView(EditorInfo attribute, boolean restarting) {
    super.onStartInputView(attribute, restarting);
    // Apply the selected keyboard to the input view.
    inputView.setKeyboard(currentKeyboard);
    inputView.closing();
  }

  /**
   * Deal with the editor reporting movement of its cursor.
   */
  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd,
      int newSelStart, int newSelEnd,
      int candidatesStart, int candidatesEnd) {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
        candidatesStart, candidatesEnd);

    // If the current selection in the text view changes, we should
    // clear whatever candidate text we have.
    if (composing.length() > 0 && (newSelStart != candidatesEnd
        || newSelEnd != candidatesEnd)) {
      composing.setLength(0);
      updateCandidates();
      InputConnection ic = getCurrentInputConnection();
      if (ic != null) {
        ic.finishComposingText();
      }
    }
  }

  /**
   * This tells us about completions that the editor has determined based
   * on the current text in it.  We want to use this in fullscreen mode
   * to show the completions ourself, since the editor can not be seen
   * in that situation.
   */
  @Override
  public void onDisplayCompletions(CompletionInfo[] completions) {
    if (enableCompletion) {
      mCompletions = completions;
      if (completions == null) {
        setSuggestions(null, false, false);
        return;
      }

      List<String> stringList = new ArrayList<String>();
      for (int i=0; i<(completions != null ? completions.length : 0); i++) {
        CompletionInfo ci = completions[i];
        if (ci != null) stringList.add(ci.getText().toString());
      }
      setSuggestions(stringList, true, true);
    }
  }

  /**
   * This translates incoming hard key events in to edit operations on an
   * InputConnection.  It is only needed when using the
   * PROCESS_HARD_KEYS option.
   */
  private boolean translateKeyDown(int keyCode, KeyEvent event) {
    metaState = MetaKeyKeyListener.handleKeyDown(metaState,
        keyCode, event);
    int c = event.getUnicodeChar(MetaKeyKeyListener.getMetaState(metaState));
    metaState = MetaKeyKeyListener.adjustMetaAfterKeypress(metaState);
    InputConnection ic = getCurrentInputConnection();
    if (c == 0 || ic == null) {
      return false;
    }

    if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0) {
      c = c & KeyCharacterMap.COMBINING_ACCENT_MASK;
    }

    if (composing.length() > 0) {
      char accent = composing.charAt(composing.length() -1 );
      int composed = KeyEvent.getDeadChar(accent, c);

      if (composed != 0) {
        c = composed;
        composing.setLength(composing.length()-1);
      }
    }

    onKey(c, null);

    return true;
  }

  /**
   * Use this to monitor key events being delivered to the application.
   * We get first crack at them, and can either resume them or let them
   * continue to the app.
   */
  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    switch (keyCode) {
    case KeyEvent.KEYCODE_BACK:
      // The InputMethodService already takes care of the back
      // key for us, to dismiss the input method if it is shown.
      // However, our keyboard could be showing a pop-up window
      // that back should dismiss, so we first allow it to do that.
      if (event.getRepeatCount() == 0 && inputView != null) {
        if (inputView.handleBack()) {
          return true;
        }
      }
      break;

    case KeyEvent.KEYCODE_DEL:
      // Special handling of the delete key: if we currently are
      // composing text for the user, we want to modify that instead
      // of let the application to the delete itself.
      if (composing.length() > 0) {
        onKey(Keyboard.KEYCODE_DELETE, null);
        return true;
      }
      break;

    case KeyEvent.KEYCODE_ENTER:
      // Let the underlying text editor always handle these.
      return false;

    default:
      // For all other keys, if we want to do transformations on
      // text being entered with a hard keyboard, we need to process
      // it and do the appropriate action.
      if (PROCESS_HARD_KEYS) {
        if (keyCode == KeyEvent.KEYCODE_SPACE
            && (event.getMetaState()&KeyEvent.META_ALT_ON) != 0) {
          // A silly example: in our input method, Alt+Space
          // is a shortcut for 'android' in lower case.
          InputConnection ic = getCurrentInputConnection();
          if (ic != null) {
            // First, tell the editor that it is no longer in the
            // shift state, since we are consuming this.
            ic.clearMetaKeyStates(KeyEvent.META_ALT_ON);
            keyDownUp(KeyEvent.KEYCODE_A);
            keyDownUp(KeyEvent.KEYCODE_N);
            keyDownUp(KeyEvent.KEYCODE_D);
            keyDownUp(KeyEvent.KEYCODE_R);
            keyDownUp(KeyEvent.KEYCODE_O);
            keyDownUp(KeyEvent.KEYCODE_I);
            keyDownUp(KeyEvent.KEYCODE_D);
            // And we consume this event.
            return true;
          }
        }
        if (enablePrediction && translateKeyDown(keyCode, event)) {
          return true;
        }
      }
    }

    return super.onKeyDown(keyCode, event);
  }

  /**
   * Use this to monitor key events being delivered to the application.
   * We get first crack at them, and can either resume them or let them
   * continue to the app.
   */
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    // If we want to do transformations on text being entered with a hard
    // keyboard, we need to process the up events to update the meta key
    // state we are tracking.
    if (PROCESS_HARD_KEYS) {
      if (enablePrediction) {
        metaState = MetaKeyKeyListener.handleKeyUp(metaState,
            keyCode, event);
      }
    }

    return super.onKeyUp(keyCode, event);
  }

  /**
   * Helper function to commit any text being composed in to the editor.
   */
  private void commitTyped(InputConnection inputConnection) {
    if (composing.length() > 0) {
      inputConnection.commitText(composing, composing.length());
      composing.setLength(0);
      updateCandidates();
    }
  }

  /**
   * Helper to update the shift state of our keyboard based on the initial
   * editor state.
   */
  private void updateShiftKeyState(EditorInfo attr) {
    if (attr != null
        && inputView != null && baseKeyboard == inputView.getKeyboard()) {
      int caps = 0;
      EditorInfo ei = getCurrentInputEditorInfo();
      if (ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
        caps = getCurrentInputConnection().getCursorCapsMode(attr.inputType);
      }
      inputView.setShifted(isCapsLockPressed || caps != 0);
    }
  }

  /**
   * Helper to determine if a given character code is alphabetic.
   */
  private boolean isAlphabet(int code) {
    if (Character.isLetter(code)) {
      return true;
    } else {
      return false;
    }
  }

  /**
   * Helper to send a key down / key up pair to the current editor.
   */
  private void keyDownUp(int keyEventCode) {
    getCurrentInputConnection().sendKeyEvent(
        new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
    getCurrentInputConnection().sendKeyEvent(
        new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
  }

  /**
   * Helper to send a character to the editor as raw key events.
   */
  private void sendKey(int keyCode) {
    switch (keyCode) {
    case '\n':
      keyDownUp(KeyEvent.KEYCODE_ENTER);
      break;
    default:
      if (keyCode >= '0' && keyCode <= '9') {
        keyDownUp(keyCode - '0' + KeyEvent.KEYCODE_0);
      } else {
        getCurrentInputConnection().commitText(String.valueOf((char) keyCode), 1);
      }
      break;
    }
  }

  // Implementation of KeyboardViewListener

  public void onKey(int primaryCode, int[] keyCodes) {
    if (isWordSeparator(primaryCode)) {
      // Handle separator
      if (composing.length() > 0) {
        commitTyped(getCurrentInputConnection());
      }
      sendKey(primaryCode);
      updateShiftKeyState(getCurrentInputEditorInfo());
    } else if (primaryCode == Keyboard.KEYCODE_DELETE) {
      handleBackspace();
    } else if (primaryCode == Keyboard.KEYCODE_SHIFT) {
      handleShift();
    } else if (primaryCode == Keyboard.KEYCODE_CANCEL) {
      handleClose();
      return;
    } else if (primaryCode == SerbianKeyboardView.KEYCODE_OPTIONS) {
      // Show a menu or somethin'
    } else if (primaryCode == Keyboard.KEYCODE_MODE_CHANGE && inputView != null) {
      Keyboard current = inputView.getKeyboard();
      if (current == symbolsKeyboard || current == symbolsShiftedKeyboard) {
        current = baseKeyboard;
      } else {
        current = symbolsKeyboard;
      }
      inputView.setKeyboard(current);
      if (current == symbolsKeyboard) {
        current.setShifted(false);
      }
    } else {
      handleCharacter(primaryCode, keyCodes);
    }
  }

  public void onText(CharSequence text) {
    InputConnection ic = getCurrentInputConnection();
    if (ic == null) return;
    ic.beginBatchEdit();
    if (composing.length() > 0) {
      commitTyped(ic);
    }
    ic.commitText(text, 0);
    ic.endBatchEdit();
    updateShiftKeyState(getCurrentInputEditorInfo());
  }

  /**
   * Update the list of available candidates from the current composing
   * text.  This will need to be filled in by however you are determining
   * candidates.
   */
  private void updateCandidates() {
    if (!enableCompletion) {
      if (composing.length() > 0) {
        ArrayList<String> list = new ArrayList<String>();
        list.add(composing.toString());
        setSuggestions(list, true, true);
      } else {
        setSuggestions(null, false, false);
      }
    }
  }

  public void setSuggestions(List<String> suggestions, boolean completions,
      boolean typedWordValid) {
    if (suggestions != null && suggestions.size() > 0) {
      setCandidatesViewShown(true);
    } else if (isExtractViewShown()) {
      setCandidatesViewShown(true);
    }
    if (candidateView != null) {
      candidateView.setSuggestions(suggestions, completions, typedWordValid);
    }
  }

  private void handleBackspace() {
    final int length = composing.length();
    if (length > 1) {
      composing.delete(length - 1, length);
      getCurrentInputConnection().setComposingText(composing, 1);
      updateCandidates();
    } else if (length > 0) {
      composing.setLength(0);
      getCurrentInputConnection().commitText("", 0);
      updateCandidates();
    } else {
      keyDownUp(KeyEvent.KEYCODE_DEL);
    }
    updateShiftKeyState(getCurrentInputEditorInfo());
  }

  private void handleShift() {
    if (inputView == null) {
      return;
    }

    Keyboard currentKeyboard = inputView.getKeyboard();
    if (baseKeyboard == currentKeyboard) {
      // Alphabet keyboard
      checkToggleCapsLock();
      inputView.setShifted(isCapsLockPressed || !inputView.isShifted());
    } else if (currentKeyboard == symbolsKeyboard) {
      symbolsKeyboard.setShifted(true);
      inputView.setKeyboard(symbolsShiftedKeyboard);
      symbolsShiftedKeyboard.setShifted(true);
    } else if (currentKeyboard == symbolsShiftedKeyboard) {
      symbolsShiftedKeyboard.setShifted(false);
      inputView.setKeyboard(symbolsKeyboard);
      symbolsKeyboard.setShifted(false);
    }
  }

  private void handleCharacter(int primaryCode, int[] keyCodes) {
    if (isInputViewShown()) {
      if (inputView.isShifted()) {
        primaryCode = Character.toUpperCase(primaryCode);
      }
    }
    if (isAlphabet(primaryCode) && enablePrediction) {
      composing.append((char) primaryCode);
      getCurrentInputConnection().setComposingText(composing, 1);
      updateShiftKeyState(getCurrentInputEditorInfo());
      updateCandidates();
    } else {
      getCurrentInputConnection().commitText(
          String.valueOf((char) primaryCode), 1);
    }
  }

  private void handleClose() {
    commitTyped(getCurrentInputConnection());
    requestHideSelf(0);
    inputView.closing();
  }

  private void checkToggleCapsLock() {
    long now = System.currentTimeMillis();
    if (lastShiftTime + 800 > now) {
      isCapsLockPressed = !isCapsLockPressed;
      lastShiftTime = 0;
    } else {
      lastShiftTime = now;
    }
  }

  private String getWordSeparators() {
    return wordSeparators;
  }

  public boolean isWordSeparator(int code) {
    String separators = getWordSeparators();
    return separators.contains(String.valueOf((char)code));
  }

  public void pickDefaultCandidate() {
    pickSuggestionManually(0);
  }

  public void pickSuggestionManually(int index) {
    if (enableCompletion && mCompletions != null && index >= 0
        && index < mCompletions.length) {
      CompletionInfo ci = mCompletions[index];
      getCurrentInputConnection().commitCompletion(ci);
      if (candidateView != null) {
        candidateView.clear();
      }
      updateShiftKeyState(getCurrentInputEditorInfo());
    } else if (composing.length() > 0) {
      // If we were generating candidate suggestions for the current
      // text, we would commit one of them here.  But for this sample,
      // we will just commit the current text.
      commitTyped(getCurrentInputConnection());
    }
  }

  public void swipeRight() {
    if (enableCompletion) {
      pickDefaultCandidate();
    }
  }

  public void swipeLeft() {
    handleBackspace();
  }

  public void swipeDown() {
    handleClose();
  }

  public void swipeUp() {
  }

  public void onPress(int primaryCode) {
  }

  public void onRelease(int primaryCode) {
  }
}
