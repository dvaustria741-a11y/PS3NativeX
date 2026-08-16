#pragma once

#include "util/atomic.hpp"
#include "Emu/Audio/AudioBackend.h"

#include "cubeb/cubeb.h"

class CubebBackend final : public AudioBackend
{
public:
	CubebBackend();
	~CubebBackend() override;

	CubebBackend(const CubebBackend&) = delete;
	CubebBackend& operator=(const CubebBackend&) = delete;

	std::string_view GetName() const override { return "Cubeb"sv; }

	bool Initialized() override;
	bool Operational() override;
	bool DefaultDeviceChanged() override;

	bool Open(std::string_view dev_id, AudioFreq freq, AudioSampleSize sample_size, AudioChannelCnt ch_cnt, audio_channel_layout layout) override;
	void Close() override;

	f64 GetCallbackFrameLen() override;

	void Play() override;
	void Pause() override;

private:
#if defined(__ANDROID__)
	// 10ms (the cross-platform default below) is a fine target on desktop
	// backends -- WASAPI/PulseAudio/CoreAudio, and cubeb_get_min_latency()
	// for them, are reliable enough that the OS mixer covers small hiccups.
	// On Android, cubeb_get_min_latency() for the AAudio/OpenSL backend
	// reports the theoretical low-latency burst size, which is only
	// actually sustainable when the audio callback thread gets
	// near-uninterrupted scheduling. That doesn't hold while the same CPU
	// is simultaneously running full PS3 emulation (PPU+SPU+RSX) on a
	// mid-range mobile SoC -- stream_latency below ends up requesting
	// ~10ms via std::max() whenever the device reports at or under that,
	// which is too tight for that workload and shows up as audible
	// crackling under load. A larger requested buffer costs a bit more
	// output latency but gives the callback thread enough headroom to
	// survive normal scheduling jitter from the emulation threads.
	static constexpr f64 AUDIO_MIN_LATENCY = 2048.0 / 48000; // ~43ms
#else
	static constexpr f64 AUDIO_MIN_LATENCY = 512.0 / 48000; // 10ms
#endif

	cubeb* m_ctx = nullptr;
	cubeb_stream* m_stream = nullptr;
#ifdef _WIN32
	bool m_com_init_success = false;
#endif

	std::array<u8, sizeof(float) * static_cast<u32>(AudioChannelCnt::SURROUND_7_1)> m_last_sample{};
	atomic_t<u8> full_sample_size = 0;

	atomic_t<bool> m_reset_req = false;

	// Protected by callback mutex
	std::string m_default_device{};

	bool m_dev_collection_cb_enabled = false;

	// Cubeb callbacks
	static long data_cb(cubeb_stream* stream, void* user_ptr, void const* input_buffer, void* output_buffer, long nframes);
	static void state_cb(cubeb_stream* stream, void* user_ptr, cubeb_state state);
	static void device_collection_changed_cb(cubeb* context, void* user_ptr);
	static void log_cb(const char *fmt, ...);

	struct device_handle
	{
		cubeb_devid handle{};
		std::string id;
		u32 ch_cnt{};
		bool valid{};
	};

	device_handle GetDevice(std::string_view dev_id = "");
};
