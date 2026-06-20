#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <cmath>
#include <algorithm>

// OpenCV headers
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>

#define LOG_TAG "SLAM_ORB_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct Point3D {
    float x, y, z;
    float r, g, b;
};

struct Pose {
    float tx, ty, tz;
    float rx, ry, rz;
};

// Global data storage
std::vector<Point3D> g_pointCloud;
std::vector<Pose> g_cameraPath;
std::mutex g_dataMutex;

// SLAM State
static cv::Mat g_K;
static cv::Mat g_T_world_curr = cv::Mat::eye(4, 4, CV_64F); // camera-to-world pose
static cv::Mat g_prevGray;
static cv::Mat g_prevRgba;
static cv::Mat g_prevDescriptors;
static std::vector<cv::KeyPoint> g_prevKeypoints;
static cv::Mat g_livePreviewRgba;
static size_t g_nextPointToReturn = 0;

// Monocular SLAM has no metric scale. This value only keeps visualization stable.
// Replace this with ARCore/depth/IMU/known-scale logic for real metric reconstruction.
static constexpr double MONOCULAR_STEP_SCALE = 0.05;
static constexpr double MIN_PARALLAX_PX = 3.0;

// More features + a less aggressive ratio test gives more visible correspondences.
// RANSAC/recoverPose still filters the matches used for pose and triangulation.
static constexpr int MAX_ORB_FEATURES = 5000;
static constexpr float LOWE_RATIO = 0.88f;
static constexpr int MAX_DISPLAY_MATCHES = 250;
static constexpr int MIN_GOOD_MATCHES = 25;
static constexpr int MIN_POSE_INLIERS = 18;

static constexpr double MAX_REPROJECTION_ERROR_PX = 4.0;
static constexpr double MIN_TRIANGULATED_DEPTH = 0.05;
static constexpr double MAX_TRIANGULATED_DEPTH = 14.0;

bool isInFrontOfCamera(const cv::Mat& transform, const cv::Mat& point4d) {
    cv::Mat cameraPoint = transform * point4d;
    return cameraPoint.at<double>(2, 0) > MIN_TRIANGULATED_DEPTH;
}

double reprojectionErrorPx(const cv::Mat& projection, const cv::Mat& point4d, const cv::Point2f& observed) {
    cv::Mat projected = projection * point4d;
    const double w = projected.at<double>(2, 0);
    if (std::fabs(w) < 1e-9) return 1e9;

    const double px = projected.at<double>(0, 0) / w;
    const double py = projected.at<double>(1, 0) / w;
    const double dx = px - static_cast<double>(observed.x);
    const double dy = py - static_cast<double>(observed.y);
    return std::sqrt(dx * dx + dy * dy);
}

void setPreviousFrame(const cv::Mat& frameGray,
                      const cv::Mat& frameRgba,
                      const std::vector<cv::KeyPoint>& keypoints,
                      const cv::Mat& descriptors) {
    frameGray.copyTo(g_prevGray);
    frameRgba.copyTo(g_prevRgba);
    g_prevKeypoints = keypoints;
    g_prevDescriptors = descriptors.clone();
}

std::string shortInfoLine(const std::string& info, size_t start, size_t length) {
    if (start >= info.size()) return std::string();
    return info.substr(start, std::min(length, info.size() - start));
}

void renderLiveDifference(cv::Mat& outputRgba,
                          const cv::Mat& currentOriginalRgba,
                          const cv::Mat& previousRgba,
                          const std::vector<cv::KeyPoint>& previousKeypoints,
                          const std::vector<cv::KeyPoint>& currentKeypoints,
                          const std::vector<cv::DMatch>& inlierMatches,
                          const std::string& info) {
    if (outputRgba.empty() || currentOriginalRgba.empty()) return;

    cv::Mat currBgr;
    cv::cvtColor(currentOriginalRgba, currBgr, cv::COLOR_RGBA2BGR);

    cv::Mat liveBgr;
    if (!previousRgba.empty() && !previousKeypoints.empty() && !currentKeypoints.empty() && !inlierMatches.empty()) {
        cv::Mat prevBgr;
        cv::cvtColor(previousRgba, prevBgr, cv::COLOR_RGBA2BGR);

        std::vector<cv::DMatch> cappedMatches;
        cappedMatches.reserve(std::min<size_t>(inlierMatches.size(), MAX_DISPLAY_MATCHES));
        for (size_t i = 0; i < inlierMatches.size() && cappedMatches.size() < MAX_DISPLAY_MATCHES; ++i) {
            cappedMatches.push_back(inlierMatches[i]);
        }

        cv::drawMatches(prevBgr, previousKeypoints,
                        currBgr, currentKeypoints,
                        cappedMatches, liveBgr,
                        cv::Scalar(0, 255, 0),
                        cv::Scalar(255, 0, 0),
                        std::vector<char>(),
                        cv::DrawMatchesFlags::NOT_DRAW_SINGLE_POINTS);
    } else {
        cv::Mat right = currBgr.clone();
        cv::putText(right, "Waiting for enough ORB matches...", cv::Point(20, 60),
                    cv::FONT_HERSHEY_SIMPLEX, 0.7, cv::Scalar(0, 255, 0), 2, cv::LINE_AA);
        cv::hconcat(currBgr, right, liveBgr);
    }

    const int w = currentOriginalRgba.cols;
    cv::line(liveBgr, cv::Point(w, 0), cv::Point(w, liveBgr.rows), cv::Scalar(255, 255, 255), 2);
    cv::putText(liveBgr, "Previous", cv::Point(20, 28),
                cv::FONT_HERSHEY_SIMPLEX, 0.75, cv::Scalar(0, 255, 0), 2, cv::LINE_AA);
    cv::putText(liveBgr, "Current + SLAM matches", cv::Point(w + 20, 28),
                cv::FONT_HERSHEY_SIMPLEX, 0.75, cv::Scalar(0, 255, 0), 2, cv::LINE_AA);

    cv::putText(liveBgr, shortInfoLine(info, 0, 55), cv::Point(w + 20, 58),
                cv::FONT_HERSHEY_SIMPLEX, 0.55, cv::Scalar(0, 255, 0), 2, cv::LINE_AA);
    cv::putText(liveBgr, shortInfoLine(info, 55, 70), cv::Point(w + 20, 84),
                cv::FONT_HERSHEY_SIMPLEX, 0.55, cv::Scalar(0, 255, 0), 2, cv::LINE_AA);

    cv::Mat liveRgba;
    cv::cvtColor(liveBgr, liveRgba, cv::COLOR_BGR2RGBA);

    // Keep the live preview wide, preserving aspect ratio. This avoids squeezing
    // the previous/current images into one normal camera frame.
    const int maxPreviewWidth = 960;
    if (liveRgba.cols > maxPreviewWidth) {
        const double scale = static_cast<double>(maxPreviewWidth) / static_cast<double>(liveRgba.cols);
        cv::resize(liveRgba, g_livePreviewRgba, cv::Size(maxPreviewWidth, static_cast<int>(liveRgba.rows * scale)), 0, 0, cv::INTER_AREA);
    } else {
        g_livePreviewRgba = liveRgba.clone();
    }

    // Return the normal camera frame to JavaCameraView. The visible, non-compressed
    // side-by-side preview is shown by an ImageView using getLivePreviewArgbNative().
    currentOriginalRgba.copyTo(outputRgba);
}

// Helper to convert Rotation matrix to Euler angles (degrees)
void getEulerAngles(const cv::Mat& R, float& rx, float& ry, float& rz) {
    double sy = std::sqrt(R.at<double>(0, 0) * R.at<double>(0, 0) +
                          R.at<double>(1, 0) * R.at<double>(1, 0));
    bool singular = sy < 1e-6;
    double x, y, z;
    if (!singular) {
        x = std::atan2(R.at<double>(2, 1), R.at<double>(2, 2));
        y = std::atan2(-R.at<double>(2, 0), sy);
        z = std::atan2(R.at<double>(1, 0), R.at<double>(0, 0));
    } else {
        x = std::atan2(-R.at<double>(1, 2), R.at<double>(1, 1));
        y = std::atan2(-R.at<double>(2, 0), sy);
        z = 0;
    }
    rx = static_cast<float>(x * 180.0 / M_PI);
    ry = static_cast<float>(y * 180.0 / M_PI);
    rz = static_cast<float>(z * 180.0 / M_PI);
}

extern "C" JNIEXPORT void JNICALL
Java_robotic_slam_SlamActivity_resetSlamNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_dataMutex);
    g_pointCloud.clear();
    g_cameraPath.clear();
    g_T_world_curr = cv::Mat::eye(4, 4, CV_64F);
    g_prevGray.release();
    g_prevRgba.release();
    g_prevDescriptors.release();
    g_prevKeypoints.clear();
    g_livePreviewRgba.release();
    g_K.release();
    g_nextPointToReturn = 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_robotic_slam_SlamActivity_processFrameNative(
        JNIEnv* env, jobject, jlong matAddrGray, jlong matAddrRgba, jint width, jint height) {

    std::lock_guard<std::mutex> lock(g_dataMutex);

    cv::Mat& frameGray = *(cv::Mat*)matAddrGray;
    cv::Mat& frameRgba = *(cv::Mat*)matAddrRgba;
    cv::Mat originalRgba = frameRgba.clone();

    // Better than focal=width only. Still an approximation; use real camera calibration for correct geometry.
    if (g_K.empty()) {
        const double fx = std::max(width, height);
        const double fy = fx;
        g_K = (cv::Mat_<double>(3, 3) <<
            fx, 0, width / 2.0,
            0, fy, height / 2.0,
            0, 0, 1);
    }

    std::vector<cv::KeyPoint> kpCurr;
    cv::Mat desCurr;
    cv::Ptr<cv::ORB> orb = cv::ORB::create(MAX_ORB_FEATURES, 1.2f, 8, 31, 0, 2, cv::ORB::HARRIS_SCORE, 31, 10);
    orb->detectAndCompute(frameGray, cv::noArray(), kpCurr, desCurr);

    int inliers = 0;
    int matchesCount = 0;
    int addedPoints = 0;
    double meanParallax = 0.0;
    bool acceptedAsKeyframe = false;
    cv::Mat previousRgbaForDisplay;
    std::vector<cv::KeyPoint> previousKeypointsForDisplay;
    std::vector<cv::DMatch> inlierMatchesForDisplay;

    if (g_prevGray.empty() || g_prevDescriptors.empty() || desCurr.empty()) {
        setPreviousFrame(frameGray, originalRgba, kpCurr, desCurr);
    } else {
        previousRgbaForDisplay = g_prevRgba.clone();
        previousKeypointsForDisplay = g_prevKeypoints;

        // Match previous -> current so the pose is also previous -> current.
        cv::BFMatcher matcher(cv::NORM_HAMMING, false);
        std::vector<std::vector<cv::DMatch>> knn;
        matcher.knnMatch(g_prevDescriptors, desCurr, knn, 2);

        std::vector<cv::Point2f> ptsPrev, ptsCurr;
        std::vector<cv::DMatch> goodMatches;
        for (const auto& pair : knn) {
            if (pair.size() < 2) continue;
            const cv::DMatch& m = pair[0];
            const cv::DMatch& n = pair[1];
            if (m.distance < LOWE_RATIO * n.distance) {
                goodMatches.push_back(m);
                ptsPrev.push_back(g_prevKeypoints[m.queryIdx].pt);
                ptsCurr.push_back(kpCurr[m.trainIdx].pt);
            }
        }
        matchesCount = static_cast<int>(ptsPrev.size());

        // Show the denser ratio-test matches in the live preview.
        // Pose estimation and 3D points are still based on RANSAC/recoverPose inliers.
        inlierMatchesForDisplay = goodMatches;

        if (matchesCount >= MIN_GOOD_MATCHES) {
            for (size_t i = 0; i < ptsPrev.size(); ++i) {
                const double dx = ptsCurr[i].x - ptsPrev[i].x;
                const double dy = ptsCurr[i].y - ptsPrev[i].y;
                meanParallax += std::sqrt(dx * dx + dy * dy);
            }
            meanParallax /= std::max<size_t>(1, ptsPrev.size());

            if (meanParallax >= MIN_PARALLAX_PX) {
                cv::Mat mask;
                cv::Mat E = cv::findEssentialMat(ptsPrev, ptsCurr, g_K, cv::RANSAC, 0.999, 1.5, mask);

                if (!E.empty() && E.rows == 3 && E.cols == 3) {
                    cv::Mat R, t;
                    int passCount = cv::recoverPose(E, ptsPrev, ptsCurr, g_K, R, t, mask);
                    inliers = passCount;

                    std::vector<cv::DMatch> ransacInlierMatches;
                    for (int i = 0; i < static_cast<int>(goodMatches.size()); ++i) {
                        if (mask.empty()) continue;
                        const uchar isInlier = (mask.rows == 1) ? mask.at<uchar>(0, i) : mask.at<uchar>(i, 0);
                        if (isInlier) ransacInlierMatches.push_back(goodMatches[i]);
                    }

                    // If enough geometric inliers exist, show them. Otherwise keep
                    // the denser ratio-test matches so the user can see tracking activity.
                    if (ransacInlierMatches.size() >= 60) {
                        inlierMatchesForDisplay = ransacInlierMatches;
                    }

                    if (passCount >= MIN_POSE_INLIERS) {
                        // Essential matrix gives only translation direction, not metric distance.
                        t *= MONOCULAR_STEP_SCALE;

                        cv::Mat T_curr_prev = cv::Mat::eye(4, 4, CV_64F); // previous-camera -> current-camera
                        R.copyTo(T_curr_prev(cv::Rect(0, 0, 3, 3)));
                        t.copyTo(T_curr_prev(cv::Rect(3, 0, 1, 3)));

                        cv::Mat T_world_prev = g_T_world_curr.clone();
                        cv::Mat T_prev_curr = T_curr_prev.inv();
                        g_T_world_curr = g_T_world_curr * T_prev_curr;

                        // Triangulate in the previous camera coordinate system.
                        cv::Mat P_prev = (cv::Mat_<double>(3, 4) <<
                            1, 0, 0, 0,
                            0, 1, 0, 0,
                            0, 0, 1, 0);
                        cv::Mat Rt_curr = T_curr_prev(cv::Rect(0, 0, 4, 3));
                        P_prev = g_K * P_prev;
                        cv::Mat P_curr = g_K * Rt_curr;

                        // triangulatePoints is safest with CV_32F inputs.
                        cv::Mat P_prev32, P_curr32;
                        P_prev.convertTo(P_prev32, CV_32F);
                        P_curr.convertTo(P_curr32, CV_32F);

                        cv::Mat pts4D;
                        cv::triangulatePoints(P_prev32, P_curr32, ptsPrev, ptsCurr, pts4D);

                        for (int i = 0; i < pts4D.cols; ++i) {
                            if (mask.empty()) continue;
                            const uchar isInlier = (mask.rows == 1) ? mask.at<uchar>(0, i) : mask.at<uchar>(i, 0);
                            if (!isInlier) continue;
                            float w = pts4D.at<float>(3, i);
                            if (std::fabs(w) < 1e-6f) continue;

                            double x = pts4D.at<float>(0, i) / w;
                            double y = pts4D.at<float>(1, i) / w;
                            double z = pts4D.at<float>(2, i) / w;
                            if (z <= MIN_TRIANGULATED_DEPTH || z > MAX_TRIANGULATED_DEPTH) continue;

                            cv::Mat ptPrev = (cv::Mat_<double>(4, 1) << x, y, z, 1.0);

                            // Keep only points that project consistently into both keyframes.
                            // This removes most floating/outlier points, so the cloud follows
                            // the observed room surfaces instead of only matching the path shape.
                            if (!isInFrontOfCamera(T_curr_prev, ptPrev)) continue;
                            const double errPrev = reprojectionErrorPx(P_prev, ptPrev, ptsPrev[i]);
                            const double errCurr = reprojectionErrorPx(P_curr, ptPrev, ptsCurr[i]);
                            if (errPrev > MAX_REPROJECTION_ERROR_PX || errCurr > MAX_REPROJECTION_ERROR_PX) continue;

                            cv::Mat ptWorld = T_world_prev * ptPrev;

                            int ix = static_cast<int>(ptsCurr[i].x);
                            int iy = static_cast<int>(ptsCurr[i].y);
                            if (ix >= 0 && ix < originalRgba.cols && iy >= 0 && iy < originalRgba.rows) {
                                cv::Vec4b color = originalRgba.at<cv::Vec4b>(iy, ix);
                                g_pointCloud.push_back({
                                    static_cast<float>(ptWorld.at<double>(0)),
                                    static_cast<float>(ptWorld.at<double>(1)),
                                    static_cast<float>(ptWorld.at<double>(2)),
                                    color[0] / 255.0f,
                                    color[1] / 255.0f,
                                    color[2] / 255.0f
                                });
                                ++addedPoints;
                            }
                        }

                        acceptedAsKeyframe = true;
                    }
                }
            }
        }

        // Keep a keyframe, not every video frame. This avoids near-zero-baseline triangulation.
        if (acceptedAsKeyframe) {
            setPreviousFrame(frameGray, originalRgba, kpCurr, desCurr);
        }
    }

    // Update camera path only once per processed frame. Renderer expects 6 floats per pose.
    Pose currPose;
    currPose.tx = static_cast<float>(g_T_world_curr.at<double>(0, 3));
    currPose.ty = static_cast<float>(g_T_world_curr.at<double>(1, 3));
    currPose.tz = static_cast<float>(g_T_world_curr.at<double>(2, 3));
    getEulerAngles(g_T_world_curr(cv::Rect(0, 0, 3, 3)), currPose.rx, currPose.ry, currPose.rz);
    g_cameraPath.push_back(currPose);

    if (g_pointCloud.size() > 200000) {
        const size_t eraseCount = 2000;
        g_pointCloud.erase(g_pointCloud.begin(), g_pointCloud.begin() + eraseCount);
        if (g_nextPointToReturn >= eraseCount) g_nextPointToReturn -= eraseCount;
        else g_nextPointToReturn = 0;
    }

    std::string info = "Matches: " + std::to_string(matchesCount) +
                       " | Inliers: " + std::to_string(inliers) +
                       " | Parallax: " + std::to_string((int)std::round(meanParallax)) +
                       " | NewPts: " + std::to_string(addedPoints) +
                       " | Pts: " + std::to_string(g_pointCloud.size());

    renderLiveDifference(frameRgba, originalRgba,
                         previousRgbaForDisplay,
                         previousKeypointsForDisplay,
                         kpCurr,
                         inlierMatchesForDisplay,
                         info);

    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_robotic_slam_SlamActivity_getCameraPathNative(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_dataMutex);
    if (g_cameraPath.empty()) return env->NewFloatArray(0);

    jfloatArray result = env->NewFloatArray(6);
    const auto& p = g_cameraPath.back();
    float data[6] = {p.tx, p.ty, p.tz, p.rx, p.ry, p.rz};
    env->SetFloatArrayRegion(result, 0, 6, data);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_robotic_slam_SlamActivity_getPointCloudNative(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_dataMutex);

    if (g_nextPointToReturn > g_pointCloud.size()) {
        g_nextPointToReturn = g_pointCloud.size();
    }

    const size_t available = g_pointCloud.size() - g_nextPointToReturn;
    const size_t count = std::min<size_t>(available, 3000);
    if (count == 0) return env->NewFloatArray(0);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(count * 6));
    std::vector<float> flat;
    flat.reserve(count * 6);

    const size_t start = g_nextPointToReturn;
    const size_t end = start + count;
    for (size_t i = start; i < end; ++i) {
        const auto& p = g_pointCloud[i];
        flat.push_back(p.x); flat.push_back(p.y); flat.push_back(p.z);
        flat.push_back(p.r); flat.push_back(p.g); flat.push_back(p.b);
    }
    g_nextPointToReturn = end;

    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_robotic_slam_SlamActivity_getLivePreviewWidthNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_dataMutex);
    return g_livePreviewRgba.empty() ? 0 : g_livePreviewRgba.cols;
}

extern "C" JNIEXPORT jint JNICALL
Java_robotic_slam_SlamActivity_getLivePreviewHeightNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_dataMutex);
    return g_livePreviewRgba.empty() ? 0 : g_livePreviewRgba.rows;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_robotic_slam_SlamActivity_getLivePreviewArgbNative(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_dataMutex);
    if (g_livePreviewRgba.empty()) return env->NewIntArray(0);

    const int width = g_livePreviewRgba.cols;
    const int height = g_livePreviewRgba.rows;
    std::vector<jint> pixels;
    pixels.reserve(static_cast<size_t>(width) * static_cast<size_t>(height));

    for (int y = 0; y < height; ++y) {
        const cv::Vec4b* row = g_livePreviewRgba.ptr<cv::Vec4b>(y);
        for (int x = 0; x < width; ++x) {
            const cv::Vec4b& px = row[x]; // RGBA
            const jint r = px[0];
            const jint g = px[1];
            const jint b = px[2];
            const jint a = px[3];
            pixels.push_back((a << 24) | (r << 16) | (g << 8) | b);
        }
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(pixels.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(pixels.size()), pixels.data());
    return result;
}

