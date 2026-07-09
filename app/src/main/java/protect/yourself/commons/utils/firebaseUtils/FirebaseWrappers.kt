package protect.yourself.commons.utils.firebaseUtils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Firebase Auth wrapper.
 *
 * Phase 2: basic wrappers for sign-in / sign-up / sign-out.
 * Phase 5: full integration with SignInSignUpPage UI.
 */
class FirebaseAuthUtil {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isSignedIn: Boolean
        get() = currentUser != null

    suspend fun signUpWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            result.user?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Sign-up returned null user"))
        } catch (t: Throwable) {
            Timber.w(t, "Sign-up failed for $email")
            Result.failure(t)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            result.user?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Sign-in returned null user"))
        } catch (t: Throwable) {
            Timber.w(t, "Sign-in failed for $email")
            Result.failure(t)
        }
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun deleteAccount(): Result<Unit> {
        return try {
            currentUser?.delete()?.await()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    companion object {
        @Volatile
        private var instance: FirebaseAuthUtil? = null

        fun getInstance(): FirebaseAuthUtil {
            return instance ?: synchronized(this) {
                instance ?: FirebaseAuthUtil().also { instance = it }
            }
        }
    }
}

/**
 * Firebase Firestore wrapper.
 *
 * Phase 2: basic CRUD helpers.
 * Phase 5: backup/sync implementation.
 */
class FirestoreUtil {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    /**
     * Save a backup document to Firestore under users/{uid}/backup/{key}.
     */
    suspend fun <T> saveBackup(uid: String, key: String, data: T): Result<Unit> {
        return try {
            db.collection("users")
                .document(uid)
                .collection("backup")
                .document(key)
                .set(data!!)
                .await()
            Result.success(Unit)
        } catch (t: Throwable) {
            Timber.w(t, "Firestore saveBackup failed")
            Result.failure(t)
        }
    }

    /**
     * Load a backup document from Firestore.
     */
    suspend fun <T> loadBackup(uid: String, key: String, clazz: Class<T>): Result<T?> {
        return try {
            val snapshot = db.collection("users")
                .document(uid)
                .collection("backup")
                .document(key)
                .get()
                .await()
            Result.success(snapshot.toObject(clazz))
        } catch (t: Throwable) {
            Timber.w(t, "Firestore loadBackup failed")
            Result.failure(t)
        }
    }

    /**
     * Save accountability partner pending request.
     * Phase 5: full implementation.
     */
    suspend fun savePendingRequest(uid: String, requestKey: String, data: Map<String, Any?>): Result<Unit> {
        return try {
            db.collection("users")
                .document(uid)
                .collection("pending_requests")
                .document(requestKey)
                .set(data)
                .await()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    companion object {
        @Volatile
        private var instance: FirestoreUtil? = null

        fun getInstance(): FirestoreUtil {
            return instance ?: synchronized(this) {
                instance ?: FirestoreUtil().also { instance = it }
            }
        }
    }
}
