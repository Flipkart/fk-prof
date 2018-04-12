package fk.prof.nfr;

import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.SessionFactory;

public class UserDAO extends AbstractDAO<User> {

    public UserDAO(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public User getById(Integer id) {
        return get(id);
    }

    public void createOrUpdate(User user) {
        persist(user);
    }
}
