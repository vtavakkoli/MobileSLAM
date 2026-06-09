package robotic.slam

object SlamDataManager {
    val capturedPoints = mutableListOf<Float>()
    val capturedPath = mutableListOf<Float>()

    @Synchronized
    fun addPoints(points: FloatArray) {
        if (points.isEmpty()) return
        for (p in points) capturedPoints.add(p)
        
        // Safety: Limit total points to avoid memory issues (approx 200k points max)
        if (capturedPoints.size > 1200000) {
            val removeCount = 6000 // remove 1000 points
            repeat(removeCount) {
                if (capturedPoints.isNotEmpty()) capturedPoints.removeAt(0)
            }
        }
    }

    @Synchronized
    fun addPathPoint(pathPoint: FloatArray) {
        if (pathPoint.size < 3) return
        for (p in pathPoint) capturedPath.add(p)
    }

    @Synchronized
    fun clear() {
        capturedPoints.clear()
        capturedPath.clear()
    }
    
    @Synchronized
    fun hasData(): Boolean = capturedPoints.isNotEmpty()
}
