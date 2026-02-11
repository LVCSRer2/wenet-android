// Copyright (c) 2021 Mobvoi Inc (authors: Xiaoyu Chen)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
#include <jni.h>
#include <cstdio>
#include <thread>

#include "decoder/asr_decoder.h"
#if defined(USE_ONNX) || defined(USE_NNAPI)
#include "decoder/onnx_asr_model.h"
#endif
#ifdef USE_TORCH
#include "torch/script.h"
#include "decoder/torch_asr_model.h"
#endif
#include "frontend/feature_pipeline.h"
#include "frontend/wav.h"
#include "post_processor/post_processor.h"
#include "utils/log.h"
#include "utils/string.h"

namespace wenet {

std::shared_ptr<DecodeOptions> decode_config;
std::shared_ptr<FeaturePipelineConfig> feature_config;
std::shared_ptr<FeaturePipeline> feature_pipeline;
std::shared_ptr<AsrDecoder> decoder;
std::shared_ptr<DecodeResource> resource;
DecodeState state = kEndBatch;
std::string total_result;  // NOLINT
std::string timed_result_json;  // JSON array of {w, s, e}
int total_samples = 0;
int endpoint_start_sample = 0;
const int kSampleRate = 16000;

// Format samples to "MM:SS.S"
std::string FormatTime(int samples) {
  float sec = static_cast<float>(samples) / kSampleRate;
  int min = static_cast<int>(sec) / 60;
  float s = sec - min * 60;
  char buf[16];
  snprintf(buf, sizeof(buf), "%02d:%04.1f", min, s);
  return std::string(buf);
}

void init(JNIEnv* env, jobject, jstring jModelDir) {
  const char* pModelDir = env->GetStringUTFChars(jModelDir, nullptr);

#ifdef USE_ONNX
  std::string modelDir = std::string(pModelDir);
  std::string dictPath = modelDir + "/units.txt";
  OnnxAsrModel::InitEngineThreads(1, false);
  auto model = std::make_shared<OnnxAsrModel>();
  model->Read(modelDir);
  LOG(INFO) << "model dir: " << modelDir;
#endif
#ifdef USE_NNAPI
  std::string modelDir = std::string(pModelDir);
  std::string dictPath = modelDir + "/units.txt";
  OnnxAsrModel::InitEngineThreads(1, true);
  auto model = std::make_shared<OnnxAsrModel>();
  model->Read(modelDir);
  LOG(INFO) << "model dir (NNAPI): " << modelDir;
#endif
#ifdef USE_TORCH
  std::string modelPath = std::string(pModelDir) + "/final.zip";
  std::string dictPath = std::string(pModelDir) + "/units.txt";
  auto model = std::make_shared<TorchAsrModel>();
  model->Read(modelPath);
  LOG(INFO) << "model path: " << modelPath;
#endif

  resource = std::make_shared<DecodeResource>();
  resource->model = model;
  resource->symbol_table =
      std::shared_ptr<fst::SymbolTable>(fst::SymbolTable::ReadText(dictPath));
  resource->unit_table = resource->symbol_table;
  LOG(INFO) << "dict path: " << dictPath;

  PostProcessOptions post_process_opts;
  resource->post_processor = std::make_shared<PostProcessor>(post_process_opts);

  feature_config = std::make_shared<FeaturePipelineConfig>(80, 16000);
  feature_pipeline = std::make_shared<FeaturePipeline>(*feature_config);

  decode_config = std::make_shared<DecodeOptions>();
  decode_config->chunk_size = 16;
#if defined(USE_ONNX) || defined(USE_NNAPI)
  decode_config->rescoring_weight = 0.0;
  decode_config->ctc_weight = 1.0;
#endif
  decoder =
      std::make_shared<AsrDecoder>(feature_pipeline, resource, *decode_config);
}

void reset(JNIEnv* env, jobject) {
  LOG(INFO) << "wenet reset";
  decoder->Reset();
  state = kEndBatch;
  total_result = "";
  timed_result_json = "";
  total_samples = 0;
  endpoint_start_sample = 0;
}

void accept_waveform(JNIEnv* env, jobject, jshortArray jWaveform) {
  jsize size = env->GetArrayLength(jWaveform);
  int16_t* waveform = env->GetShortArrayElements(jWaveform, 0);
  feature_pipeline->AcceptWaveform(waveform, size);
  total_samples += size;
  LOG(INFO) << "wenet accept waveform in ms: " << int(size / 16);
}

void set_input_finished() {
  LOG(INFO) << "wenet input finished";
  feature_pipeline->set_input_finished();
}

// Escape a string for JSON (handle \, ", control chars)
std::string JsonEscape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default: out += c;
    }
  }
  return out;
}

void AppendWordPiecesToJson(const std::vector<WordPiece>& pieces) {
  for (const auto& wp : pieces) {
    if (!timed_result_json.empty()) {
      timed_result_json += ",";
    }
    char buf[256];
    snprintf(buf, sizeof(buf), R"({"w":"%s","s":%d,"e":%d})",
             JsonEscape(wp.word).c_str(), wp.start, wp.end);
    timed_result_json += buf;
    LOG(INFO) << "word_piece: " << wp.word
              << " start=" << wp.start << " end=" << wp.end;
  }
}

void decode_thread_func() {
  while (true) {
    state = decoder->Decode();
    if (state == kEndFeats || state == kEndpoint) {
      decoder->Rescoring();
    }

    std::string result;
    if (decoder->DecodedSomething()) {
      result = decoder->result()[0].sentence;
    }

    if (state == kEndFeats) {
      LOG(INFO) << "wenet endfeats final result: " << result;
      std::string tag = " [" + FormatTime(endpoint_start_sample) + "-"
                        + FormatTime(total_samples) + "]";
      total_result += result + tag;
      if (decoder->DecodedSomething()) {
        AppendWordPiecesToJson(decoder->result()[0].word_pieces);
      }
      break;
    } else if (state == kEndpoint) {
      LOG(INFO) << "wenet endpoint final result: " << result;
      std::string tag = " [" + FormatTime(endpoint_start_sample) + "-"
                        + FormatTime(total_samples) + "]";
      total_result += result + tag + "\n";
      if (decoder->DecodedSomething()) {
        AppendWordPiecesToJson(decoder->result()[0].word_pieces);
      }
      endpoint_start_sample = total_samples;
      decoder->ResetContinuousDecoding();
    } else {
      if (decoder->DecodedSomething()) {
        LOG(INFO) << "wenet partial result: " << result;
      }
    }
  }
}

void start_decode() {
  std::thread decode_thread(decode_thread_func);
  decode_thread.detach();
}

jboolean get_finished(JNIEnv* env, jobject) {
  if (state == kEndFeats) {
    LOG(INFO) << "wenet recognize finished";
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jstring get_result(JNIEnv* env, jobject) {
  std::string result;
  if (decoder->DecodedSomething()) {
    result = decoder->result()[0].sentence;
  }
  LOG(INFO) << "wenet ui result: " << total_result + result;
  return env->NewStringUTF((total_result + result).c_str());
}

jstring get_timed_result(JNIEnv* env, jobject) {
  std::string json = "[" + timed_result_json + "]";
  return env->NewStringUTF(json.c_str());
}
}  // namespace wenet

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass c = env->FindClass("com/mobvoi/wenet/Recognize");
  if (c == nullptr) {
    return JNI_ERR;
  }

  static const JNINativeMethod methods[] = {
      {"init", "(Ljava/lang/String;)V", reinterpret_cast<void*>(wenet::init)},
      {"reset", "()V", reinterpret_cast<void*>(wenet::reset)},
      {"acceptWaveform", "([S)V",
       reinterpret_cast<void*>(wenet::accept_waveform)},
      {"setInputFinished", "()V",
       reinterpret_cast<void*>(wenet::set_input_finished)},
      {"getFinished", "()Z", reinterpret_cast<void*>(wenet::get_finished)},
      {"startDecode", "()V", reinterpret_cast<void*>(wenet::start_decode)},
      {"getResult", "()Ljava/lang/String;",
       reinterpret_cast<void*>(wenet::get_result)},
      {"getTimedResult", "()Ljava/lang/String;",
       reinterpret_cast<void*>(wenet::get_timed_result)},
  };
  int rc = env->RegisterNatives(c, methods,
                                sizeof(methods) / sizeof(JNINativeMethod));

  if (rc != JNI_OK) {
    return rc;
  }

  return JNI_VERSION_1_6;
}
