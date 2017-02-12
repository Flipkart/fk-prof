import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import { Link } from 'react-router';
import moment from 'moment';

import { fetchProfilesAction } from 'actions/ProfileActions';
import Loader from 'components/LoaderComponent';

import styles from './ProfileListComponent.css';

class ProfileListComponent extends Component {
  componentDidMount () {
    const { app, cluster, proc, start, end } = this.props;
    const startObject = moment(start);
    const endObject = moment(end);
    this.props.fetchProfiles({
      app,
      cluster,
      proc,
      query: {
        start,
        duration: endObject.diff(startObject, 'seconds'),
      },
    });
  }

  render () {
    const { profiles, app, cluster, proc, start, end } = this.props;
    if (!profiles) return null;
    if (profiles.asyncStatus === 'PENDING') {
      return (
        <Loader />
      );
    }

    if (profiles.asyncStatus === 'SUCCESS') {
      return (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Start Time</th>
              <th>End Time</th>
              <th>Worktypes</th>
            </tr>
          </thead>
          <tbody>
            {profiles.data.map(t => (
              <tr key={`${t.start}${t.end}`}>
                <td>{moment(t.start).format('Do MMM YYYY, h:mm:ss a')}</td>
                <td>{moment(t.end).format('Do MMM YYYY, h:mm:ss a')}</td>
                <td>
                  {
                    t.values.map((workType, i) =>
                      (
                        <Link
                          key={workType}
                          to={{
                            pathname: '/',
                            query: {
                              app,
                              cluster,
                              proc,
                              start,
                              end,
                              profileStart: t.start,
                              workType,
                            },
                          }}
                          htmlFor="Select worktype"
                          className={styles.workType}
                        >
                          {workType}
                          {i < (t.values.length - 1) && <span>,</span>}
                        </Link>
                      ),
                    )
                  }
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      );
    }
    return null;
  }
}

ProfileListComponent.propTypes = {
  app: PropTypes.string.isRequired,
  cluster: PropTypes.string.isRequired,
  proc: PropTypes.string.isRequired,
  start: PropTypes.string.isRequired,
  end: PropTypes.string.isRequired,
  fetchProfiles: PropTypes.func.isRequired,
  profiles: PropTypes.object.isRequired,
};

const mapStateToProps = state => ({
  profiles: state.profiles || {},
});

const mapDispatchToProps = dispatch => ({
  fetchProfiles: params => dispatch(fetchProfilesAction(params)),
});

export default connect(mapStateToProps, mapDispatchToProps)(ProfileListComponent);
