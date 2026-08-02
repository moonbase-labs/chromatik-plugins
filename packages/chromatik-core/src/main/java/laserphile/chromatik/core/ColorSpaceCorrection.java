package laserphile.chromatik.core;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import heronarts.lx.LX;

/**
 * Puts back the colour that the decoder's conversion takes out of high-definition video.
 *
 * <p>Video stores brightness and two colour-difference channels rather than red, green and blue,
 * and turning one into the other needs a set of coefficients. Which set is correct depends on the
 * standard the video was made to: standard-definition footage uses BT.601, high-definition uses
 * BT.709, and the file says which. The decoder here always uses the BT.601 set, whatever the file
 * says, because the conversion happens inside FFmpeg's scaler and JavaCV never passes the file's
 * answer along. Measured against FFmpeg's own correct conversion of the same frame, that costs up
 * to 32 of 255 on a strongly coloured pixel and nothing at all on a grey one.
 *
 * <p>The error is a fixed linear mix of the red, green and blue that came out, so undoing it is
 * another fixed linear mix: the BT.709 recipe composed with the inverse of the BT.601 one. That is
 * what {@link #CORRECTION} holds.
 *
 * <p><b>Railed channels.</b> The wrong coefficients can drive a channel past the range a byte can
 * hold, and the scaler clamps it to 0 or 255 before we ever see it. Those channels have lost the
 * information the correction would need, and pushing them further only invents colour that is not
 * there: run over a pure magenta bar, the plain correction lifts green off zero and turns it dirty.
 * So a channel that arrives sitting exactly on 0 or 255 is left where it is. Measured over the
 * reference clip, holding the rails cuts the average error from 2.78 to 0.67, against 1.24 for
 * correcting everything.
 */
public final class ColorSpaceCorrection {

  /**
   * Red, green and blue mixing weights that carry a BT.601-decoded pixel to where BT.709 would
   * have put it, one row per output channel.
   *
   * <p>Derived as {@code M709 * inverse(M601)}, where each M is the standard limited-range
   * luma-plus-colour-difference to full-range RGB matrix built from that standard's luma weights
   * (BT.601 red 0.299 blue 0.114; BT.709 red 0.2126 blue 0.0722). Both matrices operate on the same
   * gamma-encoded values the scaler works in, so composing them this way is exact rather than an
   * approximation.
   */
  private static final double[][] CORRECTION = {
    {  1.08640000, -0.07234922, -0.01405078 },
    {  0.09654619,  0.84505163,  0.05840218 },
    { -0.01410632, -0.02769368,  1.04180000 },
  };

  /**
   * Weights are held as whole numbers scaled by this much, and the scaling is divided back out
   * once the three contributions have been added together. Rounding each contribution separately
   * would let three roundings stack up, which is enough to tint a grey that should have come
   * through untouched: each row of {@link #CORRECTION} sums to exactly one, so an input with equal
   * channels has to come out unchanged.
   */
  private static final int WEIGHT_SHIFT = 16;
  private static final int WEIGHT_HALF = 1 << (WEIGHT_SHIFT - 1);

  /** Used when the source needs no correction, so callers never have to null-check. */
  public static final ColorSpaceCorrection NONE = new ColorSpaceCorrection(false);

  private final boolean enabled;

  /**
   * One table per pair of (output channel, input channel): {@code weights[output][input][value]}
   * is that input value's scaled contribution to that output channel. Nine lookups and six
   * additions per pixel, which keeps the multiplications off the per-frame path entirely.
   */
  private final int[][][] weights = new int[3][3][256];

  private ColorSpaceCorrection(boolean enabled) {
    this.enabled = enabled;

    for (int output = 0; output < 3; output++) {
      for (int input = 0; input < 3; input++) {
        for (int value = 0; value < 256; value++) {
          this.weights[output][input][value] =
            (int) Math.round(CORRECTION[output][input] * value * (1 << WEIGHT_SHIFT));
        }
      }
    }
  }

  /**
   * The correction this source needs, judged from what the file says about itself.
   *
   * Only an explicit BT.709 tag earns a correction, because that is the one case the decoder is
   * known to get wrong. Anything else, including a file that says nothing at all, is left alone:
   * the decoder's BT.601 assumption is either right or is the same guess anyone else would make,
   * and correcting on a guess would damage genuinely standard-definition footage.
   */
  public static ColorSpaceCorrection forStream(FFmpegFrameGrabber grabber, String sourceName) {
    final AVCodecParameters codecParameters = videoCodecParameters(grabber);

    if (codecParameters == null) {
      return NONE;
    }

    final int colorSpace = codecParameters.color_space();

    if (colorSpace != avutil.AVCOL_SPC_BT709) {
      return NONE;
    }

    LX.log(String.format(
      "[LaserphileVideo] %s is tagged BT.709; correcting the decoder's BT.601 conversion",
      sourceName));

    return new ColorSpaceCorrection(true);
  }

  private static AVCodecParameters videoCodecParameters(FFmpegFrameGrabber grabber) {
    final AVFormatContext format = grabber.getFormatContext();

    if (format == null) {
      return null;
    }

    for (int streamIndex = 0; streamIndex < format.nb_streams(); streamIndex++) {
      final AVStream stream = format.streams(streamIndex);

      if (stream.codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) {
        return stream.codecpar();
      }
    }

    return null;
  }

  /**
   * Rewrite a frame's pixels in place. Runs on the decode thread, before the frame is published,
   * so the engine thread never sees an uncorrected pixel and never pays for the correction.
   */
  public void applyInPlace(int[] argb) {
    if (!this.enabled) {
      return;
    }

    final int[][] toRed = this.weights[0];
    final int[][] toGreen = this.weights[1];
    final int[][] toBlue = this.weights[2];

    for (int index = 0; index < argb.length; index++) {
      final int packed = argb[index];

      final int red = (packed >> 16) & 0xff;
      final int green = (packed >> 8) & 0xff;
      final int blue = packed & 0xff;

      final int correctedRed =
        settle(red, toRed[0][red] + toRed[1][green] + toRed[2][blue]);
      final int correctedGreen =
        settle(green, toGreen[0][red] + toGreen[1][green] + toGreen[2][blue]);
      final int correctedBlue =
        settle(blue, toBlue[0][red] + toBlue[1][green] + toBlue[2][blue]);

      argb[index] =
        (packed & 0xff000000) | (correctedRed << 16) | (correctedGreen << 8) | correctedBlue;
    }
  }

  /**
   * Scale the summed contributions back down to a channel value, unless the original was already
   * pinned to one end of the range, in which case the scaler had already thrown away whatever the
   * correction would have needed.
   */
  private static int settle(int original, int scaledSum) {
    if (original == 0 || original == 255) {
      return original;
    }

    final int corrected = (scaledSum + WEIGHT_HALF) >> WEIGHT_SHIFT;

    if (corrected < 0) {
      return 0;
    }

    if (corrected > 255) {
      return 255;
    }

    return corrected;
  }
}
