import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import moment from 'moment';

import { fetchProfilesAction } from 'actions/ProfileActions';
import Loader from 'components/LoaderComponent';
import safeTraverse from 'utils/safeTraverse';
import dateFormat from 'utils/dateFormat';
import Profile from 'components/ProfileComponent';

import styles from './ProfileListComponent.css';
import 'react-treeview/react-treeview.css';


class ProfileListComponent extends Component {
  constructor(props) {
    super(props);
    this.state = {
      hideEmptyProfiles: true
    }
    this.toggleEmptyProfiles = this.toggleEmptyProfiles.bind(this);    
  }

  componentDidUpdate () {
    componentHandler.upgradeDom(); // eslint-disable-line
  }

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

  componentWillReceiveProps (nextProps) {
    const { app, cluster, proc, start, end } = nextProps;
    const didAppChange = app !== this.props.app;
    const didClusterChange = cluster !== this.props.cluster;
    const didProcChange = proc !== this.props.proc;
    const didStartDateChange = start !== this.props.start;
    if (didAppChange || didClusterChange || didProcChange || didStartDateChange) {
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
  }

  toggleEmptyProfiles() {
    this.setState({
      hideEmptyProfiles: !this.state.hideEmptyProfiles
    });
  }

  render () {
    const { profiles } = this.props;
    if (!profiles) return null;
    if (profiles.asyncStatus === 'PENDING') {
      return (
        <Loader />
      );
    }

    if (profiles.asyncStatus === 'SUCCESS') {
      if (!profiles.data.succeeded.length) {
        return <h2 className={styles.error}>No profiles found</h2>;
      }

      const sortedProfiles = profiles.data.succeeded
        .slice()
        .sort((a, b) => new Date(b.start).getTime() - new Date(a.start).getTime());
      
      const profilesWithTracesScoreAndIndicator = [];
      for(let i = 0;i<sortedProfiles.length;i++) {
        let p = sortedProfiles[i];
        if(!p.traces.length) {
          profilesWithTracesScoreAndIndicator.push([p, null, 0]);
        } else {
          let tracesScore = this.calculateTraceScoreForProfile(p);
          if(tracesScore && tracesScore.length > 0) {
            profilesWithTracesScoreAndIndicator.push([p, tracesScore, 2]);
          } else {
            profilesWithTracesScoreAndIndicator.push([p, tracesScore, 1]);
          }
        }
      }

      return (
        <div>
          <div style={{margin: "0px 4px 16px 4px"}}>
            <div style={{display: "inline-block", marginRight: "16px"}}>
              Hide empty profiles
            </div>          
            <div style={{display: "inline-block"}}>
              <label htmlFor="hideEmptySwitch" className="mdl-switch mdl-js-switch" style={{zIndex: 0}}>
                <input type="checkbox" id="hideEmptySwitch" className="mdl-switch__input"
                  checked={this.state.hideEmptyProfiles}
                  onChange={this.toggleEmptyProfiles}
                />
              </label>
            </div>  
          </div>
          <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
            {profilesWithTracesScoreAndIndicator.map((profileDetail) => {
              if(this.state.hideEmptyProfiles && profileDetail[2] != 2) {
                return null;
              }

              const startTime = new Date(profileDetail[0].start);
              const endTime = new Date(startTime.getTime() + (profileDetail[0].duration * 1000));
              return (
                <Profile
                  key={profileDetail[0].start}
                  heading={`
                    ${dateFormat(startTime, 'profileList')} - ${dateFormat(endTime, 'profileList')}
                  `}
                  profile={profileDetail[0]}
                  tracesScore={profileDetail[1]}
                />
              );
            })}
          </div>
        </div>
      );
    }
    return null;
  }

  calculateTraceScoreForProfile(profile) {
    return Object.keys(profile.ws_summary)
    .reduce((scores, workType) => {
      profile.ws_summary[workType].traces.forEach((trace) => {
        scores[trace.trace_idx] = scores[trace.trace_idx] || 0;
        scores[trace.trace_idx] += trace.props.samples;
      });
      return scores;
    }, [])
    .map((eachScore, i) => ({
      score: eachScore,
      name: profile.traces[i],
    }))
    .filter(x => x !== undefined)
    .sort((a, b) => b.score - a.score);
  }
}

ProfileListComponent.propTypes = {
  app: PropTypes.string.isRequired,
  cluster: PropTypes.string.isRequired,
  proc: PropTypes.string.isRequired,
  start: PropTypes.string.isRequired,
  end: PropTypes.number.isRequired,
  fetchProfiles: PropTypes.func.isRequired,
  profiles: PropTypes.object.isRequired,
};

const mapStateToProps = state => ({
  profiles: state.profiles || {},
  traces: safeTraverse(state, ['traces']),
});

const mapDispatchToProps = dispatch => ({
  fetchProfiles: params => dispatch(fetchProfilesAction(params)),
});

export default connect(mapStateToProps, mapDispatchToProps)(ProfileListComponent);
