package player

// AudioPipeline controls playback properties such as jitter buffer and playback speed
type AudioPipeline struct {
	JitterMs int
	Speed    float64
}

func NewAudioPipeline(speed float64) *AudioPipeline {
	return &AudioPipeline{
		JitterMs: 300,
		Speed:    speed,
	}
}

