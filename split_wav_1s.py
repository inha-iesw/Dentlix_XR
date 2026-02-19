# python split_wav_1s.py --input long.wav --out unknown --sr 16000 --chunk_sec 1.0

import argparse
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf


def split_wav(input_path, out_dir, sr=16000, chunk_sec=1.0):
    y, _ = librosa.load(input_path, sr=sr, mono=True)
    chunk_len = int(sr * chunk_sec)
    n_chunks = int(np.ceil(len(y) / chunk_len))

    out_dir.mkdir(parents=True, exist_ok=True)

    for i in range(n_chunks):
        start = i * chunk_len
        end = start + chunk_len
        chunk = y[start:end]
        if len(chunk) < chunk_len:
            chunk = np.pad(chunk, (0, chunk_len - len(chunk)), mode='constant')
        out_path = out_dir / f'{input_path.stem}_{i:05d}.wav'
        sf.write(out_path, chunk, sr)

    return n_chunks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True, help='Input wav path')
    parser.add_argument('--out', required=True, help='Output folder for 1s chunks')
    parser.add_argument('--sr', type=int, default=16000)
    parser.add_argument('--chunk_sec', type=float, default=1.0)
    args = parser.parse_args()

    input_path = Path(args.input)
    out_dir = Path(args.out)

    if not input_path.exists():
        raise SystemExit(f'Input not found: {input_path}')

    n = split_wav(input_path, out_dir, sr=args.sr, chunk_sec=args.chunk_sec)
    print(f'Saved {n} chunks to {out_dir}')


if __name__ == '__main__':
    main()
