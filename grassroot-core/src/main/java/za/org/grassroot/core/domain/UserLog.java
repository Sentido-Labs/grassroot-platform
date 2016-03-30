package za.org.grassroot.core.domain;

import za.org.grassroot.core.enums.UserLogType;
import za.org.grassroot.core.util.UIDGenerator;

import javax.persistence.*;
import java.time.Instant;

/**
 * Created by luke on 2016/02/22.
 */
@Entity
@Table(name="user_log",
        uniqueConstraints = {@UniqueConstraint(name = "uk_user_log_request_uid", columnNames = "uid")})
public class UserLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="id", nullable = false)
    private Long id;

    @Column(name = "uid", nullable = false, length = 50)
    private String uid;

    @Basic
    @Column(name="creation_time", nullable = false, updatable = false)
    private Instant creationTime;

    @Basic
    @Column(name="user_log_type", nullable = false)
    private UserLogType userLogType;

    @Basic
    @Column(name="user_uid", nullable = false)
    private String userUid;

    @Basic
    @Column(name="description", length = 255)
    private String description;

    private UserLog() {
        // for JPA
    }

    public UserLog(String userUid, UserLogType userLogType) {
        this.uid = UIDGenerator.generateId();
        this.creationTime = Instant.now();
        this.userUid = userUid;
        this.userLogType = userLogType;
    }

    public UserLog(String userUid, UserLogType userLogType, String description) {
        this(userUid, userLogType);
        this.description = description;
    }

    @PreUpdate
    @PrePersist
    public void updateTimeStamps() {
        if (creationTime == null) {
            creationTime = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getUid() { return uid; }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public UserLogType getUserLogType() {
        return userLogType;
    }

    public String getUserUid() {
        return userUid;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "UserLog{" +
                "id=" + id +
                ", creationTime =" + creationTime +
                ", userUid=" + userUid +
                ", userLogType=" + userLogType +
                ", description='" + description + '\'' +
                '}';
    }

    @Override
    public int hashCode() {
        return (getUid() != null) ? getUid().hashCode() : 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }

        final UserLog that = (UserLog) o;

        if (getUid() != null ? !getUid().equals(that.getUid()) : that.getUid() != null) { return false; }

        return true;

    }
}
