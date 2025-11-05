// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class VoskActivity extends Activity implements
        RecognitionListener {

    private static final String TAG = "VoskActivity";

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_MIC = 3;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private Model model;
    private SpeechService speechService;
    private TextView resultView;
    private Spinner languageSpinner;
    private String currentLanguage = "en";

    @Override
    public void onCreate(Bundle state) {
        try {
            Log.d(TAG, "onCreate called");
            super.onCreate(state);
            
            // Set up global exception handler for uncaught exceptions
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    Log.e(TAG, "Uncaught exception in thread " + thread.getName(), throwable);
                    runOnUiThread(() -> {
                        if (resultView != null) {
                            resultView.setText("Uncaught exception: " + throwable.getMessage());
                        }
                    });
                }
            });
            
            setContentView(R.layout.main);

            // Setup layout
            resultView = findViewById(R.id.result_text);
            languageSpinner = findViewById(R.id.language_spinner);
            
            if (resultView == null) {
                Log.e(TAG, "resultView is null");
                return;
            }
            if (languageSpinner == null) {
                Log.e(TAG, "languageSpinner is null");
                return;
            }
            
            setUiState(STATE_START);

            // Setup language spinner
            try {
                String[] languages = {getString(R.string.language_english), getString(R.string.language_farsi)};
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, languages);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                languageSpinner.setAdapter(adapter);
                languageSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                        try {
                            currentLanguage = (position == 0) ? "en" : "fa";
                            Log.d(TAG, "Language changed to: " + currentLanguage);
                            if (model != null) {
                                initModel(); // Reinitialize model for new language
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error in language selection", e);
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error setting up language spinner", e);
                setErrorState("Error setting up language selection: " + e.getMessage());
                return;
            }

            findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
            ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

            LibVosk.setLogLevel(LogLevel.INFO);

            // Check if user has given permission to record audio, init the model after permission is granted
            int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            } else {
                initModel();
            }
        } catch (Exception e) {
            Log.e(TAG, "Critical error in onCreate", e);
            if (resultView != null) {
                resultView.setText("Critical error: " + e.getMessage());
            }
        }
    }

    private void initModel() {
        try {
            Log.d(TAG, "Initializing model for language: " + currentLanguage);
            String modelName = currentLanguage.equals("en") ? "model-en-us" : "model-fa";
            Log.d(TAG, "Using model: " + modelName);
            
            StorageService.unpack(this, modelName, modelName,
                    (model) -> {
                        try {
                            Log.d(TAG, "Model loaded successfully");
                            this.model = model;
                            setUiState(STATE_READY);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in model callback", e);
                            setErrorState("Error initializing model: " + e.getMessage());
                        }
                    },
                    (exception) -> {
                        try {
                            Log.e(TAG, "Model loading failed", exception);
                            String errorMessage = exception != null ? exception.getMessage() : "Unknown error occurred";
                            if (errorMessage == null) {
                                errorMessage = "Failed to unpack the model";
                            } else {
                                errorMessage = "Failed to unpack the model: " + errorMessage;
                            }
                            setErrorState(errorMessage);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in exception handler", e);
                            setErrorState("Critical error during model loading");
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error in initModel", e);
            setErrorState("Failed to initialize model: " + e.getMessage());
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

    }

    @Override
    public void onResult(String hypothesis) {
        try {
            Log.d(TAG, "onResult called with hypothesis: " + (hypothesis != null ? hypothesis : "null"));
            if (hypothesis != null && !hypothesis.trim().isEmpty()) {
                resultView.append(hypothesis + "\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onResult", e);
            setErrorState("Error processing result: " + e.getMessage());
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        try {
            Log.d(TAG, "onFinalResult called with hypothesis: " + (hypothesis != null ? hypothesis : "null"));
            if (hypothesis != null && !hypothesis.trim().isEmpty()) {
                resultView.append(hypothesis + "\n");
            }
            setUiState(STATE_DONE);
        } catch (Exception e) {
            Log.e(TAG, "Error in onFinalResult", e);
            setErrorState("Error processing final result: " + e.getMessage());
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        try {
            Log.d(TAG, "onPartialResult called with hypothesis: " + (hypothesis != null ? hypothesis : "null"));
            if (hypothesis != null && !hypothesis.trim().isEmpty()) {
                resultView.append(hypothesis + "\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onPartialResult", e);
            setErrorState("Error processing partial result: " + e.getMessage());
        }
    }

    @Override
    public void onError(Exception e) {
        try {
            Log.e(TAG, "onError called", e);
            String errorMessage = e != null ? e.getMessage() : "Unknown error occurred";
            if (errorMessage == null) {
                errorMessage = "Unknown error occurred";
            }
            setErrorState(errorMessage);
        } catch (Exception ex) {
            Log.e(TAG, "Error in onError handler", ex);
            if (resultView != null) {
                resultView.setText("Critical error in error handler");
            }
        }
    }

    @Override
    public void onTimeout() {
        try {
            Log.d(TAG, "onTimeout called");
            setUiState(STATE_DONE);
        } catch (Exception e) {
            Log.e(TAG, "Error in onTimeout", e);
            setErrorState("Error handling timeout: " + e.getMessage());
        }
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                languageSpinner.setEnabled(true);
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                languageSpinner.setEnabled(true);
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                ((ToggleButton) findViewById(R.id.pause)).setChecked(false);
                languageSpinner.setEnabled(true);
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                languageSpinner.setEnabled(false);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        try {
            Log.e(TAG, "Setting error state: " + message);
            if (message == null) {
                message = "Unknown error occurred";
            }
            if (resultView != null) {
                resultView.setText(message);
            }
            if (findViewById(R.id.recognize_mic) != null) {
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_mic).setEnabled(false);
            }
            if (languageSpinner != null) {
                languageSpinner.setEnabled(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in setErrorState", e);
            if (resultView != null) {
                resultView.setText("Critical error in error handling");
            }
        }
    }


    private void recognizeMicrophone() {
        try {
            Log.d(TAG, "recognizeMicrophone called");
            if (speechService != null) {
                Log.d(TAG, "Stopping existing speech service");
                setUiState(STATE_DONE);
                speechService.stop();
                speechService.shutdown();
                speechService = null;
            } else {
                if (model == null) {
                    Log.w(TAG, "Model is null, cannot start recognition");
                    setErrorState("Model not loaded. Please wait for initialization to complete.");
                    return;
                }
                Log.d(TAG, "Starting microphone recognition");
                setUiState(STATE_MIC);
                try {
                    Recognizer rec = new Recognizer(model, 16000.0f);
                    speechService = new SpeechService(rec, 16000.0f);
                    speechService.startListening(this);
                    Log.d(TAG, "Speech service started successfully");
                } catch (IOException e) {
                    Log.e(TAG, "IOException in recognizeMicrophone", e);
                    String errorMessage = e != null ? e.getMessage() : "Unknown error occurred";
                    if (errorMessage == null) {
                        errorMessage = "Failed to start recognition";
                    }
                    setErrorState(errorMessage);
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error in recognizeMicrophone", e);
                    setErrorState("Unexpected error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Critical error in recognizeMicrophone", e);
            setErrorState("Critical error: " + e.getMessage());
        }
    }


    private void pause(boolean checked) {
        try {
            Log.d(TAG, "pause called with checked: " + checked);
            if (speechService != null) {
                speechService.setPause(checked);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in pause method", e);
            setErrorState("Error pausing recognition: " + e.getMessage());
        }
    }

}
