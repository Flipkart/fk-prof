import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import { Link } from 'react-router';
import moment from 'moment';

import { fetchProfilesAction } from 'actions/ProfileActions';

import styles from './ProfileListComponent.css';

class ProfileListComponent extends Component {
  componentDidMount () {
    const { app, cluster, proc, startTime, endTime } = this.props;
    const startObject = moment(startTime);
    const endObject = moment(endTime);
    this.props.fetchProfiles({
      app,
      cluster,
      proc,
      query: {
        startTime,
        duration: endObject.diff(startObject, 'seconds'),
      },
    });
  }

  render () {
    const { profiles, app, cluster, proc, startTime, endTime } = this.props;
    if (!profiles) return null;
    if (profiles.asyncStatus === 'PENDING') {
      return (
        <div
          className="mdl-progress mdl-js-progress mdl-progress__indeterminate"
        />
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
              <tr>
                <td>{t.start}</td>
                <td>{t.end}</td>
                <td>
                  {
                    t.values.map((workType, i) =>
                      (
                        <Link
                          to={{
                            pathname: '/',
                            query: { app, cluster, proc, startTime, endTime, workType },
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
  startTime: PropTypes.string.isRequired,
  endTime: PropTypes.string.isRequired,
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
