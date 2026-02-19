## 사용법:
## python train_kws_dscnn.py --data data --labels 확대,초기화,unknown,silence --epochs 20

import argparse
import os
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
import librosa

# Simple DS-CNN for KWS on log-mel features
class DepthwiseSeparableConv(nn.Module):
    def __init__(self, in_ch, out_ch, k=3, s=1, p=1):
        super().__init__()
        self.dw = nn.Conv2d(in_ch, in_ch, kernel_size=k, stride=s, padding=p, groups=in_ch, bias=False)
        self.pw = nn.Conv2d(in_ch, out_ch, kernel_size=1, stride=1, padding=0, bias=False)
        self.bn = nn.BatchNorm2d(out_ch)

    def forward(self, x):
        x = self.dw(x)
        x = self.pw(x)
        x = self.bn(x)
        return F.relu(x)


class DSCNN(nn.Module):
    def __init__(self, n_classes):
        super().__init__()
        self.conv0 = nn.Sequential(
            nn.Conv2d(1, 64, kernel_size=3, stride=1, padding=1, bias=False),
            nn.BatchNorm2d(64),
            nn.ReLU(),
        )
        self.ds1 = DepthwiseSeparableConv(64, 64)
        self.ds2 = DepthwiseSeparableConv(64, 64)
        self.ds3 = DepthwiseSeparableConv(64, 128, s=2)
        self.ds4 = DepthwiseSeparableConv(128, 128)
        self.ds5 = DepthwiseSeparableConv(128, 256, s=2)
        self.ds6 = DepthwiseSeparableConv(256, 256)
        self.pool = nn.AdaptiveAvgPool2d((1, 1))
        self.fc = nn.Linear(256, n_classes)

    def forward(self, x):
        x = self.conv0(x)
        x = self.ds1(x)
        x = self.ds2(x)
        x = self.ds3(x)
        x = self.ds4(x)
        x = self.ds5(x)
        x = self.ds6(x)
        x = self.pool(x)
        x = x.view(x.size(0), -1)
        return self.fc(x)


class KWSDataset(Dataset):
    def __init__(
        self,
        root,
        labels,
        sr=16000,
        n_mels=40,
        win_ms=25,
        hop_ms=10,
        fixed_len=1.0,
    ):
        self.root = Path(root)
        self.labels = labels
        self.sr = sr
        self.n_mels = n_mels
        self.win_length = int(sr * win_ms / 1000)
        self.hop_length = int(sr * hop_ms / 1000)
        self.fixed_samples = int(sr * fixed_len)
        self.items = []
        for idx, name in enumerate(labels):
            d = self.root / name
            if not d.exists():
                continue
            for p in d.rglob('*.wav'):
                self.items.append((p, idx))

    def __len__(self):
        return len(self.items)

    def _fix_len(self, y):
        if len(y) > self.fixed_samples:
            return y[: self.fixed_samples]
        if len(y) < self.fixed_samples:
            pad = self.fixed_samples - len(y)
            return np.pad(y, (0, pad), mode='constant')
        return y

    def __getitem__(self, idx):
        path, label = self.items[idx]
        y, sr = librosa.load(path, sr=self.sr, mono=True)
        y = self._fix_len(y)
        mel = librosa.feature.melspectrogram(
            y=y,
            sr=self.sr,
            n_fft=self.win_length * 2,
            hop_length=self.hop_length,
            win_length=self.win_length,
            n_mels=self.n_mels,
            power=2.0,
        )
        logmel = librosa.power_to_db(mel + 1e-10)
        # Normalize per-sample
        logmel = (logmel - logmel.mean()) / (logmel.std() + 1e-6)
        # Shape: (n_mels, time) -> (1, n_mels, time)
        x = torch.tensor(logmel, dtype=torch.float32).unsqueeze(0)
        return x, label


def train(args):
    labels = [x.strip() for x in args.labels.split(',') if x.strip()]
    dataset = KWSDataset(
        args.data,
        labels,
        n_mels=args.n_mels,
    )
    if len(dataset) == 0:
        raise RuntimeError('No wav files found. Check data folder and labels.')

    # Simple split
    n_val = max(1, int(0.1 * len(dataset)))
    n_train = len(dataset) - n_val
    train_ds, val_ds = torch.utils.data.random_split(dataset, [n_train, n_val])

    train_loader = DataLoader(train_ds, batch_size=args.batch, shuffle=True, num_workers=0)
    val_loader = DataLoader(val_ds, batch_size=args.batch, shuffle=False, num_workers=0)

    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    model = DSCNN(n_classes=len(labels)).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    criterion = nn.CrossEntropyLoss()

    for epoch in range(1, args.epochs + 1):
        model.train()
        total_loss = 0.0
        for x, y in train_loader:
            x, y = x.to(device), y.to(device)
            opt.zero_grad()
            logits = model(x)
            loss = criterion(logits, y)
            loss.backward()
            opt.step()
            total_loss += loss.item()

        model.eval()
        correct = 0
        total = 0
        with torch.no_grad():
            for x, y in val_loader:
                x, y = x.to(device), y.to(device)
                logits = model(x)
                pred = torch.argmax(logits, dim=1)
                correct += (pred == y).sum().item()
                total += y.size(0)

        acc = 100.0 * correct / max(1, total)
        print(f'Epoch {epoch}: loss={total_loss/len(train_loader):.4f} val_acc={acc:.2f}%')

    os.makedirs(args.out, exist_ok=True)
    ckpt_path = os.path.join(args.out, 'dscnn_kws.pt')
    torch.save({'model_state': model.state_dict(), 'labels': labels}, ckpt_path)
    print(f'Saved: {ckpt_path}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', required=True, help='Data root with label subfolders')
    parser.add_argument('--labels', required=True, help='Comma-separated labels in folder order')
    parser.add_argument('--out', default='out_kws', help='Output dir')
    parser.add_argument('--epochs', type=int, default=20)
    parser.add_argument('--batch', type=int, default=32)
    parser.add_argument('--lr', type=float, default=1e-3)
    parser.add_argument('--n_mels', type=int, default=40)
    args = parser.parse_args()
    train(args)
