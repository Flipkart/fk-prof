import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import Select, {Creatable} from 'react-select';

import fetchProfilesClusterAction from 'actions/ProfilesProcActions';
import safeTraverse from 'utils/safeTraverse';

import styles from './ProcSelectComponent.css';

import fetchPoliciesProcsAction from 'actions/PoliciesProcActions';
import debounce from 'utils/debounce';

const noop = () => {};

class ProcSelectComponent extends Component {
  componentDidMount () {
    const { cluster, app } = this.props;
    if (cluster && app) {
      this.props.isPoliciesPage ? this.props.getPoliciesProcs(app)(cluster)() : this.props.getProfilesProcs(app)(cluster)();
    }
  }

  componentWillReceiveProps(nextProps) {
    const didAppChange = nextProps.app !== this.props.app;
    const didClusterChange = nextProps.cluster !== this.props.cluster;
    if (didAppChange || didClusterChange || nextProps.isPoliciesPage !== this.props.isPoliciesPage) {
      if (nextProps.isPoliciesPage) {
        this.props.getPoliciesProcs(nextProps.app)(nextProps.cluster)();
      } else {
        this.props.getProfilesProcs(nextProps.app)(nextProps.cluster)();
      }
    }
  }

  render() {
    const {onChange, profilesProcs, policiesProcs} = this.props;
    const procsResponse = this.props.isPoliciesPage ? policiesProcs : profilesProcs;
    const procs = procsResponse.asyncStatus === 'SUCCESS'
      ? procsResponse.data.map(c => ({name: c})) : [];
    const valueOption = this.props.value && {name: this.props.value};
    return (
      <div>
        <label className={styles.label} htmlFor="proc">Process</label>
        {this.props.isPoliciesPage &&
        <Creatable
          id="proc"
          clearable={false}
          options={procs}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getPoliciesProcs(this.props.app)(this.props.cluster), 500)}
          isLoading={procsResponse.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={procsResponse.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
          promptTextCreator={(label) => "Add process: " + label}
          onOpen={()=>(this.props.value && this.creatable.select && this.creatable.select.setState({inputValue: this.props.value}))}
          ref={(ref)=> this.creatable = ref}
        />}
        {!this.props.isPoliciesPage &&
        <Select
          id="proc"
          clearable={false}
          options={procs}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getProfilesProcs(this.props.app)(this.props.cluster), 500)}
          isLoading={procsResponse.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={procsResponse.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
          onOpen={()=>(this.props.value && this.select && this.select.setState({inputValue: this.props.value}))}
          ref={(ref)=> this.select = ref}
        />}
      </div>
    );
  }
}

const mapStateToProps = (state, ownProps) => ({
  profilesProcs: safeTraverse(state, ['profilesProcs', ownProps.cluster]) || {},
  policiesProcs: safeTraverse(state, ['policiesProcs', ownProps.cluster]) || {},
});

const mapDispatchToProps = dispatch => ({
  getProfilesProcs: app => cluster => event => dispatch(fetchProfilesClusterAction(app, cluster, event)),
  getPoliciesProcs: app => cluster => event => dispatch(fetchPoliciesProcsAction(app, cluster, event)),
});

ProcSelectComponent.propTypes = {
  app: PropTypes.string,
  cluster: PropTypes.string,
  profilesProcs: PropTypes.object.isRequired,
  policiesProcs: PropTypes.object.isRequired,
  getProfilesProcs: PropTypes.func.isRequired,
  getPoliciesProcs: PropTypes.func.isRequired,
  onChange: PropTypes.func,
  value: PropTypes.string,
  isPoliciesPage: PropTypes.bool.isRequired,
};

export default connect(mapStateToProps, mapDispatchToProps)(ProcSelectComponent);
