package fk.prof.nfr;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "user")
public class User {

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "accessId")
    private Integer accessId;

    public User() {
    }

    public User(Integer id, Integer accessId) {
        this.id = id;
        this.accessId = accessId;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getAccessId() {
        return accessId;
    }

    public void setAccessId(Integer accessId) {
        this.accessId = accessId;
    }
}
