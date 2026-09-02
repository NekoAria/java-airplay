#!/usr/bin/env python3
"""Generate a multi-resolution AirPlay-style ICO using only the Python standard library."""

from __future__ import annotations

import argparse
import math
import struct
from pathlib import Path

SIZES = (16, 24, 32, 48, 64, 128, 256)
TOP_LEFT = (36, 153, 239)
BOTTOM_RIGHT = (13, 71, 161)
WHITE = (255, 255, 255)


def clamp(value: float, lower: float, upper: float) -> float:
    if value < lower:
        return lower
    if value > upper:
        return upper
    return value


def inside_rounded_square(x: float, y: float, size: int) -> bool:
    margin = size * 0.04
    radius = size * 0.20
    nearest_x = clamp(x, margin + radius, size - margin - radius)
    nearest_y = clamp(y, margin + radius, size - margin - radius)
    dx = x - nearest_x
    dy = y - nearest_y
    return dx * dx + dy * dy <= radius * radius


def inside_monitor_stroke(x: float, y: float, size: int) -> bool:
    left = size * 0.15
    right = size * 0.85
    top = size * 0.19
    bottom = size * 0.70
    half_width = max(1.0, size * 0.022)
    outer = (
        left - half_width <= x <= right + half_width
        and top - half_width <= y <= bottom + half_width
    )
    inner = (
        left + half_width < x < right - half_width
        and top + half_width < y < bottom - half_width
    )
    return outer and not inner


def inside_airplay_triangle(x: float, y: float, size: int) -> bool:
    center = size * 0.5
    apex_y = size * 0.62
    base_y = size * 0.86
    if y < apex_y or y > base_y:
        return False
    half_width = size * 0.14 * ((y - apex_y) / (base_y - apex_y))
    return abs(x - center) <= half_width


def gradient(x: float, y: float, size: int) -> tuple[int, int, int]:
    blend = clamp((x / size) * 0.40 + (y / size) * 0.75, 0.0, 1.0)
    return tuple(
        round(start + (end - start) * blend)
        for start, end in zip(TOP_LEFT, BOTTOM_RIGHT)
    )


def render(size: int) -> bytes:
    samples = 4
    sample_count = samples * samples
    pixels = bytearray(size * size * 4)
    for pixel_y in range(size):
        for pixel_x in range(size):
            coverage = 0
            red = green = blue = 0.0
            for sample_y in range(samples):
                for sample_x in range(samples):
                    x = pixel_x + (sample_x + 0.5) / samples
                    y = pixel_y + (sample_y + 0.5) / samples
                    if not inside_rounded_square(x, y, size):
                        continue
                    sample_red, sample_green, sample_blue = gradient(x, y, size)
                    if inside_monitor_stroke(x, y, size) or inside_airplay_triangle(x, y, size):
                        sample_red, sample_green, sample_blue = WHITE
                    red += sample_red
                    green += sample_green
                    blue += sample_blue
                    coverage += 1
            offset = (pixel_y * size + pixel_x) * 4
            if coverage:
                pixels[offset] = round(blue / coverage)
                pixels[offset + 1] = round(green / coverage)
                pixels[offset + 2] = round(red / coverage)
            pixels[offset + 3] = round(255 * coverage / sample_count)
    return bytes(pixels)


def bitmap_entry(pixels: bytes, size: int) -> bytes:
    mask_stride = math.ceil(size / 32) * 4
    header = struct.pack(
        "<IiiHHIIiiII",
        40,
        size,
        size * 2,
        1,
        32,
        0,
        size * size * 4 + mask_stride * size,
        0,
        0,
        0,
        0,
    )
    rows = [pixels[row * size * 4 : (row + 1) * size * 4] for row in range(size)]
    xor_bitmap = b"".join(reversed(rows))
    and_mask = bytes(mask_stride * size)
    return header + xor_bitmap + and_mask


def write_icon(output: Path) -> None:
    blobs = [bitmap_entry(render(size), size) for size in SIZES]
    offset = 6 + 16 * len(SIZES)
    directory = bytearray(struct.pack("<HHH", 0, 1, len(SIZES)))
    for size, blob in zip(SIZES, blobs):
        encoded_size = 0 if size == 256 else size
        directory.extend(
            struct.pack(
                "<BBBBHHII", encoded_size, encoded_size, 0, 0, 1, 32, len(blob), offset
            )
        )
        offset += len(blob)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as icon:
        icon.write(directory)
        for blob in blobs:
            icon.write(blob)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    write_icon(args.output)


if __name__ == "__main__":
    main()
